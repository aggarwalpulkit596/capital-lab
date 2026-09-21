package capital.underwriting

import capital.policy.BASIS_POINTS_PER_WHOLE
import java.math.BigInteger

enum class Verdict {
    ALLOW,
    /** Fund nothing automatically; a human decides. */
    REVIEW,
    /** Do not fund, and do not queue for routine review. */
    BLOCK;

    fun escalate(other: Verdict) = if (other.ordinal > ordinal) other else this
}

data class FraudSignal(
    val code: String,
    val verdict: Verdict,
    val explanation: String,
    val evidence: Map<String, Any>,
)

data class FraudAssessment(
    val verdict: Verdict,
    val signals: List<FraudSignal>,
    val policyVersion: String,
) {
    val explanation: String
        get() =
            if (signals.isEmpty()) "No configured abuse signal fired."
            else
                "$verdict on ${signals.size} signal(s): " +
                    signals.joinToString("; ") { "${it.code} (${it.verdict})" }
}

/**
 * What a payout request looks like at the moment it is made, in the terms this lab can actually
 * observe. Anything requiring device fingerprints, identity documents, or cross-tenant graphs is
 * deliberately absent rather than faked.
 */
data class RequestContext(
    val requestedCents: Long,
    val observation: Observation,
    /** Average daily gross over the preceding baseline, or null when history is too short. */
    val baselineDailyGrossCents: Long?,
    /** Refund share observed since the most recent advance settled, in basis points. */
    val refundRateSinceFundingBasisPoints: Int? = null,
)

data class FraudPolicy(
    val version: String = "illustrative-fraud-v1",
    /** A destination changed this recently makes a payout reviewable. */
    val destinationCoolingDays: Int = 7,
    val spikeMultiple: Int = 3,
    val newAccountDays: Int = 14,
    val newAccountLargeRequestCents: Long = 500_000,
    val postFundingRefundSpikeBasisPoints: Int = 2_500,
    val chargebackBlockBasisPoints: Int = 1_500,
) {
    init {
        require(version.isNotBlank())
        require(destinationCoolingDays >= 0 && newAccountDays >= 0)
        require(spikeMultiple >= 2)
        require(newAccountLargeRequestCents >= 0)
        require(postFundingRefundSpikeBasisPoints in 1..BASIS_POINTS_PER_WHOLE)
        require(chargebackBlockBasisPoints in 1..BASIS_POINTS_PER_WHOLE)
    }
}

/**
 * Illustrative abuse rules over observable behavior.
 *
 * These are rules, not a classifier: there is no training data, no score, and no calibration, so a
 * signal firing is grounds for a human to look, never a finding of fraud. Only one condition blocks
 * outright — chargebacks at a level that is itself evidence of disputed transactions — and even
 * that is described as a stop, not a verdict about intent.
 *
 * The composite verdict is the most severe signal, so adding a rule can never soften an existing
 * one. Every signal carries the numbers it fired on, so a decision can be re-derived later.
 */
fun assessFraud(
    context: RequestContext,
    policy: FraudPolicy = FraudPolicy(),
): FraudAssessment {
    require(context.requestedCents >= 0)
    val signals = mutableListOf<FraudSignal>()
    val observation = context.observation

    observation.daysSinceDestinationChange?.let { days ->
        if (days < policy.destinationCoolingDays)
            signals +=
                FraudSignal(
                    "RECENT_DESTINATION_CHANGE",
                    Verdict.REVIEW,
                    "The payout destination changed $days day(s) ago, inside the " +
                        "${policy.destinationCoolingDays}-day cooling period. Paying a newly changed destination is " +
                        "the step an account takeover needs; the change itself is not proof of one.",
                    mapOf(
                        "daysSinceChange" to days,
                        "coolingDays" to policy.destinationCoolingDays,
                    ),
                )
    }

    val baseline = context.baselineDailyGrossCents
    val latest = observation.dailyGrossCents.lastOrNull()
    if (baseline != null && baseline > 0 && latest != null) {
        val threshold =
            BigInteger.valueOf(baseline) * BigInteger.valueOf(policy.spikeMultiple.toLong())
        if (BigInteger.valueOf(latest) >= threshold)
            signals +=
                FraudSignal(
                    "REVENUE_SPIKE",
                    Verdict.REVIEW,
                    "The latest day's gross of $latest cents is at least ${policy.spikeMultiple}x the " +
                        "$baseline cent baseline. Sales can genuinely spike; advancing against an unusual day " +
                        "before it settles is what makes this worth a look.",
                    mapOf(
                        "latestDailyGrossCents" to latest,
                        "baselineDailyGrossCents" to baseline,
                        "multiple" to policy.spikeMultiple,
                    ),
                )
    }

    if (
        observation.tenureDays < policy.newAccountDays &&
            context.requestedCents >= policy.newAccountLargeRequestCents
    )
        signals +=
            FraudSignal(
                "NEW_ACCOUNT_LARGE_REQUEST",
                Verdict.REVIEW,
                "A request of ${context.requestedCents} cents against only ${observation.tenureDays} day(s) " +
                    "of observed history. There is not yet enough behavior to distinguish a fast-growing app " +
                    "from a fabricated one.",
                mapOf(
                    "tenureDays" to observation.tenureDays,
                    "requestedCents" to context.requestedCents,
                    "thresholdCents" to policy.newAccountLargeRequestCents,
                ),
            )

    context.refundRateSinceFundingBasisPoints?.let { rate ->
        if (rate >= policy.postFundingRefundSpikeBasisPoints)
            signals +=
                FraudSignal(
                    "POST_FUNDING_REFUND_SPIKE",
                    Verdict.REVIEW,
                    "Refunds since the last advance settled reached ${rate / 100.0}% of gross. Refunds " +
                        "concentrated immediately after funding are the pattern a receivable-financing product " +
                        "is most exposed to.",
                    mapOf(
                        "refundRateBasisPoints" to rate,
                        "thresholdBasisPoints" to policy.postFundingRefundSpikeBasisPoints,
                    ),
                )
    }

    if (observation.grossCents > 0) {
        val chargebackRate =
            (BigInteger.valueOf(observation.chargebackCents) *
                    BigInteger.valueOf(BASIS_POINTS_PER_WHOLE.toLong()) /
                    BigInteger.valueOf(observation.grossCents))
                .min(BigInteger.valueOf(Int.MAX_VALUE.toLong()))
                .toInt()
        if (chargebackRate >= policy.chargebackBlockBasisPoints)
            signals +=
                FraudSignal(
                    "CHARGEBACK_LEVEL",
                    Verdict.BLOCK,
                    "Chargebacks are ${chargebackRate / 100.0}% of observed gross, at or above the " +
                        "${policy.chargebackBlockBasisPoints / 100.0}% stop. These are already-disputed " +
                        "transactions, so the receivable being advanced against is itself contested.",
                    mapOf(
                        "chargebackRateBasisPoints" to chargebackRate,
                        "thresholdBasisPoints" to policy.chargebackBlockBasisPoints,
                    ),
                )
    }

    return FraudAssessment(
        signals.fold(Verdict.ALLOW) { worst, it -> worst.escalate(it.verdict) },
        signals,
        policy.version,
    )
}
