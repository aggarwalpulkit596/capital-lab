package capital.settlement

import java.time.Instant

/**
 * One payment actually received from a store. A store pays once for many pools, so a remittance
 * carries a line per pool. `finalLine` is the store declaring that no further money is coming for
 * that pool; it is not an assertion that the pool's principal was repaid.
 */
data class Remittance(
    val id: String,
    val store: String,
    val developerId: String,
    val receivedCents: Long,
    val receivedAt: Instant,
    val lines: List<RemittanceLine>,
    val currency: String = "USD",
)

data class RemittanceLine(val poolId: String, val cents: Long, val finalLine: Boolean = false)

/** What the waterfall did to one pool's line. */
data class LineAllocation(
    val poolId: String,
    val lineCents: Long,
    val recoveryCents: Long,
    val principalCents: Long,
    val residualCents: Long,
    val poolClosed: Boolean,
    val outstandingAfterCents: Long,
)

data class RemittanceResult(
    val status: RemittanceStatus,
    val remittanceId: String,
    val appliedCents: Long,
    val unappliedCents: Long,
    val lines: List<LineAllocation>,
    val explanation: String,
) {
    val recoveredCents: Long
        get() = lines.sumOf { it.recoveryCents }

    val principalCents: Long
        get() = lines.sumOf { it.principalCents }

    val residualCents: Long
        get() = lines.sumOf { it.residualCents }
}

enum class RemittanceStatus {
    /** Every cent was allocated to recovery, principal, or developer residual. */
    APPLIED,
    /** Allocated, but some cash could not be attributed to a pool and is held unapplied. */
    PARTIALLY_APPLIED,
    /** Nothing was posted. The remittance is unusable as presented. */
    REJECTED,
    /** This remittance id was already applied; the repeat had no additional effect. */
    ALREADY_APPLIED,
}

/** Why a pool's expected proceeds fell after we had already advanced against them. */
enum class RevisionReason {
    REFUND,
    CHARGEBACK,
    STORE_ADJUSTMENT,
}

data class ProceedsRevision(
    val id: String,
    val poolId: String,
    val reason: RevisionReason,
    val reductionCents: Long,
    val observedAt: Instant,
)

data class RevisionResult(
    val status: RevisionStatus,
    val proceedsBeforeCents: Long,
    val proceedsAfterCents: Long,
    val effectiveLimitCents: Long,
    val outstandingCents: Long,
    /** Principal moved from "expected from the store" to "recoverable from the developer". */
    val reclassifiedCents: Long,
    val openRecoveryCents: Long,
    val explanation: String,
)

enum class RevisionStatus {
    /** Proceeds fell but outstanding principal still sits within the revised limit. */
    WITHIN_LIMIT,
    /** Outstanding principal now exceeds the revised limit; the excess became recoverable. */
    RECLASSIFIED,
    ALREADY_APPLIED,
    REJECTED,
}

data class ReturnResult(
    val status: ReturnStatus,
    val principalCents: Long,
    val cashCents: Long,
    val explanation: String,
)

enum class ReturnStatus {
    REVERSED,
    ALREADY_REVERSED,
    REJECTED,
}

data class ResidualResult(
    val status: ResidualStatus,
    val requestedCents: Long,
    val recoveredCents: Long,
    val paidCents: Long,
    val explanation: String,
)

enum class ResidualStatus {
    PAID,
    /** Every available cent went to open recovery; nothing was released. */
    FULLY_RECOVERED,
    NOTHING_PAYABLE,
    ALREADY_PAID,
    REJECTED,
}
