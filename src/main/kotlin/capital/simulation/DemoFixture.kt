package capital.simulation

import capital.ingestion.PublicDataset
import capital.payments.AdvanceRequest
import capital.payments.Database
import capital.payments.update
import capital.policy.FinancialTerms
import capital.risk.MonitoringPolicy
import capital.risk.RiskInputs
import java.time.Instant
import java.time.LocalDate

/**
 * Synthetic input data lives here, never in the payment or risk services. Amounts are USD cents.
 */
data class DemoFixture(
    val developerId: String = "developer-1",
    val poolId: String = "pool-1",
    val destination: String = "verified-destination-v1",
    val evaluatedAt: Instant = Instant.parse("2026-09-19T18:05:00Z"),
    val downloadedAt: Instant = Instant.parse("2026-09-19T18:00:00Z"),
    val expectedPeriod: LocalDate = LocalDate.parse("2026-09-18"),
    val reportThrough: LocalDate = expectedPeriod,
    val cashCents: Long = 100_000,
    val developerLimitCents: Long = 200_000,
    val proceedsCents: Long = 100_000,
    val fundedCents: Long = 60_000,
    val outstandingCents: Long = fundedCents,
    val requestedPrincipalCents: Long = 20_000,
    val riskInputs: RiskInputs =
        RiskInputs(100_000, 0, 100_000, MonitoringPolicy.DEMO_V1.baselineObservations),
) {
    fun request() = AdvanceRequest(developerId, poolId, requestedPrincipalCents, destination)

    fun seed(capital: Database, bank: Database) {
        capital.transaction { connection ->
            connection.update("INSERT INTO treasury(id,cash_cents) VALUES (1,?)", cashCents)
            connection.update(
                "INSERT INTO developers(id,limit_cents,outstanding_cents,destination_version) VALUES (?,?,?,?)",
                developerId,
                developerLimitCents,
                outstandingCents,
                destination,
            )
            connection.update(
                """INSERT INTO pools(id,developer_id,net_proceeds_cents,funded_lifetime_cents,outstanding_cents,report_through,downloaded_at)
                    VALUES (?,?,?,?,?,?,?)""",
                poolId,
                developerId,
                proceedsCents,
                fundedCents,
                outstandingCents,
                reportThrough,
                downloadedAt,
            )
        }
        bank.transaction { it.update("INSERT INTO bank_balance(id,cents) VALUES (1,?)", cashCents) }
    }
}

fun seedScenario(capital: Database, bank: Database) = DemoFixture().seed(capital, bank)

/** Scenario recipes build immutable inputs before creating either database's seed state. */
fun fixtureFor(
    scenario: String,
    datasetDate: String?,
    terms: FinancialTerms,
    monitoring: MonitoringPolicy,
): DemoFixture {
    val base = DemoFixture()
    return when (scenario) {
        "stale" -> base.copy(reportThrough = base.expectedPeriod.minusDays(1))
        "refund" -> base.copy(proceedsCents = 70_000)
        "liquidity" -> base.copy(cashCents = 10_000)
        "fraud" ->
            base.copy(
                proceedsCents = 400_000,
                riskInputs = base.riskInputs.copy(grossCents = 400_000),
            )
        "cancellations" ->
            base.copy(
                proceedsCents = 85_000,
                riskInputs = base.riskInputs.copy(cancellationCents = 15_000),
            )
        "public-data" -> {
            val date = requireNotNull(datasetDate)
            val net = PublicDataset.toDemoUsd(PublicDataset.day(date)["netMinor"].asLong)
            val limit = maxOf(net, 1)
            base.copy(
                proceedsCents = net,
                developerLimitCents = limit,
                cashCents = maxOf(Math.multiplyExact(limit, 2), base.cashCents),
                outstandingCents = 0,
                fundedCents = 0,
                requestedPrincipalCents = terms.advanceLimit(net).coerceAtLeast(1),
                riskInputs = PublicDataset.inputs(date, monitoring),
            )
        }
        else -> base
    }
}
