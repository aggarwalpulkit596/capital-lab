package capital.underwriting

import capital.payments.Database
import capital.payments.rows
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Assembles observed history from recorded state and runs the underwriting model over it.
 *
 * The model itself is pure and lives in [RiskModel]; this only gathers inputs. Nothing here changes
 * a limit or blocks a payout: the result is an assessment a caller can read, and the authoritative
 * controls remain the ones enforced under lock in the reservation path. Wiring these numbers into
 * funding decisions would need calibration and a credit owner, which this lab does not have.
 */
class UnderwritingService(
    private val database: Database,
    private val clock: Clock,
    private val policy: UnderwritingPolicy = UnderwritingPolicy.ILLUSTRATIVE_V1,
    private val fraudPolicy: FraudPolicy = FraudPolicy(),
) {

    data class Assessment(
        val developerId: String,
        val observation: Observation,
        val rate: AdvanceRateDecision,
        val exposure: ExposureAssessment,
        val fraud: FraudAssessment,
        val steppedLimitCents: Long,
        val policyVersion: String,
    )

    fun assess(developerId: String, poolId: String, requestedCents: Long): Assessment? {
        require(requestedCents >= 0)
        return database.transaction { connection ->
            val developer =
                connection
                    .rows(
                        "SELECT limit_cents,outstanding_cents FROM developers WHERE id=?",
                        developerId,
                    ) {
                        it.getLong(1) to it.getLong(2)
                    }
                    .singleOrNull() ?: return@transaction null

            val poolOutstanding =
                connection
                    .rows(
                        "SELECT outstanding_cents FROM pools WHERE id=? AND developer_id=?",
                        poolId,
                        developerId,
                    ) {
                        it.getLong(1)
                    }
                    .singleOrNull() ?: return@transaction null

            val observation = observe(connection, developerId)
            val windowStart =
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                    .minusDays(policy.velocityWindowDays.toLong())
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
            val fundedInWindow =
                connection
                    .rows(
                        """SELECT coalesce(sum(principal_cents),0) FROM advances
                            WHERE developer_id=? AND state IN ('SETTLED','DISPATCHING','UNKNOWN','READY')
                            AND created_at >= ?""",
                        developerId,
                        windowStart,
                    ) {
                        it.getLong(1)
                    }
                    .single()

            Assessment(
                developerId,
                observation,
                advanceRate(observation, policy),
                assessExposure(
                    requestedCents,
                    poolOutstanding,
                    developer.second,
                    fundedInWindow,
                    observation,
                    policy,
                ),
                assessFraud(
                    RequestContext(requestedCents, observation, baselineDailyGross(observation)),
                    fraudPolicy,
                ),
                stepUpLimit(developer.first, observation, policy),
                policy.version,
            )
        }
    }

    /**
     * Observed history from the pools themselves.
     *
     * The lab records cumulative proceeds per pool rather than a daily series, so each pool
     * contributes one observation. A production reader would use the normalized daily store
     * reports; treating a pool as one day would overstate tenure, so tenure is counted from the
     * oldest pool's report coverage instead.
     */
    private fun observe(connection: java.sql.Connection, developerId: String): Observation {
        val pools =
            connection.rows(
                """SELECT net_proceeds_cents,report_through FROM pools
                    WHERE developer_id=? ORDER BY report_through""",
                developerId,
            ) {
                it.getLong(1) to it.getDate(2).toLocalDate()
            }
        if (pools.isEmpty()) return Observation(0, 0, 0, 0, listOf())

        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val tenure =
            Math.toIntExact(
                java.time.temporal.ChronoUnit.DAYS.between(pools.first().second, today)
                    .coerceAtLeast(0)
            )
        val gross = pools.sumOf { it.first.coerceAtLeast(0) }

        // Refund evidence is the recorded downward revisions, not an estimate.
        val revisions =
            connection
                .rows(
                    """SELECT coalesce(sum(CASE WHEN r.reason='CHARGEBACK' THEN r.reduction_cents ELSE 0 END),0),
                        coalesce(sum(CASE WHEN r.reason<>'CHARGEBACK' THEN r.reduction_cents ELSE 0 END),0)
                        FROM proceeds_revisions r JOIN pools p ON p.id=r.pool_id WHERE p.developer_id=?""",
                    developerId,
                ) {
                    it.getLong(1) to it.getLong(2)
                }
                .single()

        val repaid =
            connection
                .rows(
                    "SELECT count(*) FROM pools WHERE developer_id=? AND closed AND outstanding_cents=0",
                    developerId,
                ) {
                    it.getLong(1)
                }
                .single()

        return Observation(
            tenureDays = tenure,
            grossCents = gross,
            refundCents = revisions.second,
            chargebackCents = revisions.first,
            dailyGrossCents = pools.map { it.first.coerceAtLeast(0) },
            repaidPools = Math.toIntExact(repaid),
        )
    }

    private fun baselineDailyGross(observation: Observation): Long? {
        val prior = observation.dailyGrossCents.dropLast(1)
        if (prior.isEmpty()) return null
        return prior.sum() / prior.size
    }
}
