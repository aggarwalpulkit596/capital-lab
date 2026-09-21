package capital

import capital.policy.FinancialTerms
import capital.policy.applyBasisPoints
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** All amounts are USD cents. This snapshot describes one unsettled receivable pool. */
data class Snapshot(
    val poolId: String,
    val snapshotId: String,
    val reportThroughDate: LocalDate,
    val downloadedAt: Instant,
    val netProceedsCents: Long,
    val existingExposureCents: Long,
)

data class Policy(
    val version: String,
    val exposureCeilingCents: Long,
    val advanceBasisPoints: Int = FinancialTerms.EARLY_PAYOUTS_V1.advanceBasisPoints,
    val feeBasisPoints: Int = FinancialTerms.EARLY_PAYOUTS_V1.feeBasisPoints,
    // Extra whole reporting days tolerated beyond the source adapter's expected period.
    // Zero by default. This is illustrative policy, not a store publication calendar.
    val allowedReportingLagDays: Int = 0,
)

data class EvaluationContext(
    val evaluatedAt: Instant,
    val expectedReportThroughDate: LocalDate,
    val reportingZone: ZoneId,
)

enum class Status {
    ELIGIBLE,
    HOLD,
    NO_CAPACITY,
}

enum class Reason {
    REPORT_BEHIND_ALLOWED_PERIOD,
    REPORT_WITHIN_DELAY_ALLOWANCE,
    EXISTING_EXPOSURE_ABOVE_LIMIT,
    NO_ADDITIONAL_CAPACITY,
    CAPACITY_AVAILABLE,
}

/** A quote and its explanation, not a payment authorization or a reservation. */
data class Decision(
    val snapshot: Snapshot,
    val policy: Policy,
    val context: EvaluationContext,
    val minimumReportThroughDate: LocalDate,
    val status: Status,
    val reasons: List<Reason>,
    val effectiveLimitCents: Long,
    // Diagnostic calculation from the supplied data, even when held.
    val arithmeticCapacityCents: Long,
    val excessExposureCents: Long,
    // Actionable quote is zero on hold; do not mistake diagnostic capacity for eligibility.
    val eligiblePrincipalCents: Long,
    val feeCents: Long,
    val netCashCents: Long,
)

fun evaluate(snapshot: Snapshot, policy: Policy, context: EvaluationContext): Decision {
    require(snapshot.poolId.isNotBlank() && snapshot.snapshotId.isNotBlank()) {
        "Pool and snapshot IDs are required"
    }
    require(policy.version.isNotBlank()) { "Policy version is required" }
    require(snapshot.existingExposureCents >= 0) { "Exposure cannot be negative" }
    require(policy.exposureCeilingCents >= 0) { "Exposure ceiling cannot be negative" }
    require(policy.advanceBasisPoints in 0..10_000) { "Advance rate must be between 0 and 100%" }
    require(policy.feeBasisPoints in 0..10_000) { "Fee rate must be between 0 and 100%" }
    require(policy.allowedReportingLagDays in 0..7) {
        "Demo reporting allowance must be between 0 and 7 days"
    }
    require(!snapshot.downloadedAt.isAfter(context.evaluatedAt)) {
        "Download timestamp is in the future"
    }
    val downloadDate = snapshot.downloadedAt.atZone(context.reportingZone).toLocalDate()
    val evaluationDate = context.evaluatedAt.atZone(context.reportingZone).toLocalDate()
    require(!snapshot.reportThroughDate.isAfter(downloadDate)) {
        "Report coverage is after its download date"
    }
    require(!context.expectedReportThroughDate.isAfter(evaluationDate)) {
        "Expected report coverage is in the future"
    }

    val minimumDate =
        context.expectedReportThroughDate.minusDays(policy.allowedReportingLagDays.toLong())
    val held = snapshot.reportThroughDate.isBefore(minimumDate)
    val receivableLimit =
        applyBasisPoints(
            snapshot.netProceedsCents.coerceAtLeast(0),
            policy.advanceBasisPoints,
            false,
        )
    val limit = minOf(receivableLimit, policy.exposureCeilingCents)
    // Both operands are nonnegative Long values, so these differences cannot overflow.
    val capacity = (limit - snapshot.existingExposureCents).coerceAtLeast(0)
    val excess = (snapshot.existingExposureCents - limit).coerceAtLeast(0)
    val principal = if (held) 0L else capacity
    val fee = applyBasisPoints(principal, policy.feeBasisPoints, true)
    val reasons = buildList {
        if (held) add(Reason.REPORT_BEHIND_ALLOWED_PERIOD)
        else if (snapshot.reportThroughDate.isBefore(context.expectedReportThroughDate)) {
            add(Reason.REPORT_WITHIN_DELAY_ALLOWANCE)
        }
        if (excess > 0) add(Reason.EXISTING_EXPOSURE_ABOVE_LIMIT)
        if (capacity == 0L) add(Reason.NO_ADDITIONAL_CAPACITY)
        if (principal > 0) add(Reason.CAPACITY_AVAILABLE)
    }
    return Decision(
        snapshot,
        policy,
        context,
        minimumDate,
        when {
            held -> Status.HOLD
            capacity == 0L -> Status.NO_CAPACITY
            else -> Status.ELIGIBLE
        },
        reasons,
        limit,
        capacity,
        excess,
        principal,
        fee,
        principal - fee,
    )
}
