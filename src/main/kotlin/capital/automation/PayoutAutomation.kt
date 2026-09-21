package capital.automation

import capital.payments.AdvanceRequest
import capital.payments.AdvanceService
import capital.payments.Database
import capital.payments.FundingDeclined
import capital.payments.rows
import capital.payments.update
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

enum class PayoutMode {
    MANUAL,
    AUTOMATIC,
}

enum class Cadence {
    DAILY,
    WEEKLY;

    /** The cycle date a given day belongs to, so a weekly policy funds once per ISO week. */
    fun cycleDate(day: LocalDate): LocalDate =
        when (this) {
            DAILY -> day
            WEEKLY -> day.minusDays((day.dayOfWeek.value - 1).toLong())
        }
}

data class PayoutPolicy(
    val developerId: String,
    val mode: PayoutMode,
    val minimumCents: Long,
    val maximumCents: Long = 0,
    val cadence: Cadence = Cadence.DAILY,
    val paused: Boolean = false,
) {
    init {
        require(developerId.isNotBlank())
        require(minimumCents >= 0 && maximumCents >= 0)
        require(maximumCents == 0L || maximumCents >= minimumCents) {
            "A per-cycle maximum below the minimum would never fund anything"
        }
    }
}

enum class PoolOutcome {
    REQUESTED,
    BELOW_MINIMUM,
    NO_CAPACITY,
    BLOCKED,
    FAILED,
}

data class PoolDecision(
    val poolId: String,
    val outcome: PoolOutcome,
    val availableCents: Long,
    val requestedCents: Long,
    val advanceId: UUID?,
    val reason: String?,
)

data class CycleResult(
    val status: CycleStatus,
    val cycleId: String?,
    val cycleDate: LocalDate?,
    val decisions: List<PoolDecision>,
    val explanation: String,
) {
    val requestedCents: Long
        get() = decisions.sumOf { it.requestedCents }

    val fundedPools: Int
        get() = decisions.count { it.outcome == PoolOutcome.REQUESTED }
}

enum class CycleStatus {
    RAN,
    /** This developer's cycle for this period already ran; nothing was re-evaluated. */
    ALREADY_RAN,
    NOT_AUTOMATIC,
    PAUSED,
    NO_POLICY,
}

/**
 * Evaluates every open pool for a developer on an automatic payout schedule and reserves the
 * capacity each one currently supports, with no human request.
 *
 * Two properties make this safe to run repeatedly, which matters because a scheduler will:
 * - A cycle is unique per developer and period, so a second run in the same period does nothing.
 * - Each reservation uses a derived idempotency key (`auto:pool:cycle-date`), so even if the cycle
 *   row were lost, replaying it would return the original advance rather than fund a second one.
 *
 * The scheduler never overrides policy. It asks [AdvanceService.available] for capacity and
 * requests at most that, so holds, limits, destination changes, and stale reports block an
 * automatic payout exactly as they block a manual one. Skipped pools are recorded with their reason
 * rather than silently omitted.
 */
class PayoutScheduler(
    private val database: Database,
    private val advances: AdvanceService,
    private val clock: Clock,
) {
    fun setPolicy(policy: PayoutPolicy) {
        database.transaction { connection ->
            connection.rows(
                "SELECT id FROM developers WHERE id=? FOR UPDATE",
                policy.developerId,
            ) {}
            connection.update(
                """INSERT INTO payout_policies(developer_id,mode,minimum_cents,maximum_cents,cadence,paused,updated_at)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (developer_id) DO UPDATE SET mode=EXCLUDED.mode,minimum_cents=EXCLUDED.minimum_cents,
                    maximum_cents=EXCLUDED.maximum_cents,cadence=EXCLUDED.cadence,paused=EXCLUDED.paused,
                    updated_at=EXCLUDED.updated_at""",
                policy.developerId,
                policy.mode.name,
                policy.minimumCents,
                policy.maximumCents,
                policy.cadence.name,
                policy.paused,
                clock.instant(),
            )
        }
    }

    fun policy(developerId: String): PayoutPolicy? = database.transaction { connection ->
        readPolicy(connection, developerId)
    }

    private fun readPolicy(connection: java.sql.Connection, developerId: String): PayoutPolicy? =
        connection
            .rows(
                "SELECT mode,minimum_cents,maximum_cents,cadence,paused FROM payout_policies WHERE developer_id=?",
                developerId,
            ) {
                PayoutPolicy(
                    developerId,
                    PayoutMode.valueOf(it.getString(1)),
                    it.getLong(2),
                    it.getLong(3),
                    Cadence.valueOf(it.getString(4)),
                    it.getBoolean(5),
                )
            }
            .singleOrNull()

    fun runCycle(developerId: String): CycleResult {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val policy =
            policy(developerId)
                ?: return CycleResult(
                    CycleStatus.NO_POLICY,
                    null,
                    null,
                    listOf(),
                    "No payout policy is configured for $developerId.",
                )
        if (policy.mode != PayoutMode.AUTOMATIC)
            return CycleResult(
                CycleStatus.NOT_AUTOMATIC,
                null,
                null,
                listOf(),
                "$developerId is on manual payouts; automatic cycles do not apply.",
            )
        if (policy.paused)
            return CycleResult(
                CycleStatus.PAUSED,
                null,
                null,
                listOf(),
                "Automatic payouts are paused for $developerId.",
            )

        val cycleDate = policy.cadence.cycleDate(today)
        val cycleId = "cycle:$developerId:$cycleDate"

        // Claim the period first. A duplicate key here means another run already owns this cycle.
        val claimed = database.transaction { connection ->
            connection.rows("SELECT id FROM developers WHERE id=? FOR UPDATE", developerId) {}
            val existing =
                connection
                    .rows(
                        "SELECT id FROM payout_cycles WHERE developer_id=? AND cycle_date=?",
                        developerId,
                        cycleDate,
                    ) {
                        it.getString(1)
                    }
                    .singleOrNull()
            if (existing != null) return@transaction false
            connection.update(
                """INSERT INTO payout_cycles(id,developer_id,cycle_date,evaluated_pools,funded_pools,requested_cents,created_at)
                        VALUES (?,?,?,0,0,0,?)""",
                cycleId,
                developerId,
                cycleDate,
                clock.instant(),
            )
            true
        }
        if (!claimed)
            return CycleResult(
                CycleStatus.ALREADY_RAN,
                cycleId,
                cycleDate,
                readItems(cycleId),
                "The $cycleDate cycle for $developerId already ran. Nothing was re-evaluated.",
            )

        val pools = database.transaction { connection ->
            connection.rows(
                "SELECT id FROM pools WHERE developer_id=? AND NOT closed ORDER BY id",
                developerId,
            ) {
                it.getString(1)
            }
        }

        val decisions = pools.map { evaluatePool(developerId, it, policy, cycleId) }
        database.transaction { connection ->
            decisions.forEach {
                connection.update(
                    """INSERT INTO payout_cycle_items(cycle_id,pool_id,outcome,available_cents,requested_cents,advance_id,reason)
                        VALUES (?,?,?,?,?,?,?)""",
                    cycleId,
                    it.poolId,
                    it.outcome.name,
                    it.availableCents,
                    it.requestedCents,
                    it.advanceId,
                    it.reason,
                )
            }
            connection.update(
                "UPDATE payout_cycles SET evaluated_pools=?,funded_pools=?,requested_cents=? WHERE id=?",
                decisions.size,
                decisions.count { it.outcome == PoolOutcome.REQUESTED },
                decisions.sumOf { it.requestedCents },
                cycleId,
            )
            connection.update(
                "UPDATE payout_policies SET last_cycle=? WHERE developer_id=?",
                cycleDate,
                developerId,
            )
        }

        val funded = decisions.count { it.outcome == PoolOutcome.REQUESTED }
        val skipped = decisions.size - funded
        return CycleResult(
            CycleStatus.RAN,
            cycleId,
            cycleDate,
            decisions,
            "Evaluated ${decisions.size} open pool(s) for $cycleDate: $funded reserved " +
                "${decisions.sumOf { it.requestedCents }} cents, $skipped skipped with a recorded reason.",
        )
    }

    private fun evaluatePool(
        developerId: String,
        poolId: String,
        policy: PayoutPolicy,
        cycleId: String,
    ): PoolDecision {
        val availability =
            try {
                advances.available(developerId, poolId)
            } catch (failure: FundingDeclined) {
                return PoolDecision(poolId, PoolOutcome.FAILED, 0, 0, null, failure.message)
            }
        if (availability.blockedReason != null)
            return PoolDecision(
                poolId,
                PoolOutcome.BLOCKED,
                0,
                0,
                null,
                availability.blockedReason,
            )
        if (availability.principalCents == 0L)
            return PoolDecision(poolId, PoolOutcome.NO_CAPACITY, 0, 0, null, null)

        val capped =
            if (policy.maximumCents > 0) minOf(availability.principalCents, policy.maximumCents)
            else availability.principalCents
        if (capped < policy.minimumCents)
            return PoolDecision(
                poolId,
                PoolOutcome.BELOW_MINIMUM,
                availability.principalCents,
                0,
                null,
                "Below the ${policy.minimumCents} cent minimum for this policy",
            )

        val destination = database.transaction { connection ->
            connection
                .rows(
                    "SELECT destination_version FROM developers WHERE id=?",
                    developerId,
                ) {
                    it.getString(1)
                }
                .single()
        }
        return try {
            val advance =
                advances.reserve(
                    AdvanceRequest(developerId, poolId, capped, destination),
                    "auto:$poolId:${cycleId.substringAfterLast(':')}",
                )
            PoolDecision(
                poolId,
                PoolOutcome.REQUESTED,
                availability.principalCents,
                advance.principalCents,
                advance.id,
                null,
            )
        } catch (failure: FundingDeclined) {
            // Capacity can change between the read and the locked re-check. Policy wins.
            PoolDecision(
                poolId,
                PoolOutcome.FAILED,
                availability.principalCents,
                0,
                null,
                failure.message,
            )
        }
    }

    private fun readItems(cycleId: String): List<PoolDecision> =
        database.transaction { connection ->
            connection.rows(
                """SELECT pool_id,outcome,available_cents,requested_cents,advance_id,reason
                    FROM payout_cycle_items WHERE cycle_id=? ORDER BY pool_id""",
                cycleId,
            ) {
                PoolDecision(
                    it.getString(1),
                    PoolOutcome.valueOf(it.getString(2)),
                    it.getLong(3),
                    it.getLong(4),
                    it.getObject(5, UUID::class.java),
                    it.getString(6),
                )
            }
        }
}
