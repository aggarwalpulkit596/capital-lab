package capital.underwriting

import capital.policy.BASIS_POINTS_PER_WHOLE
import java.math.BigInteger

/**
 * Observed history for one developer, as whole days of normalized store activity.
 *
 * Every figure is something we watched happen. Nothing here is projected, smoothed, or filled in: a
 * missing day is a shorter window, not a zero, because treating an unreported day as zero sales
 * would understate volatility and overstate tenure.
 */
data class Observation(
    val tenureDays: Int,
    val grossCents: Long,
    val refundCents: Long,
    val chargebackCents: Long,
    val dailyGrossCents: List<Long>,
    /** Pools fully repaid through store proceeds, used only to step a limit up. */
    val repaidPools: Int = 0,
    /** Days since the payout destination last changed, or null if it never has. */
    val daysSinceDestinationChange: Int? = null,
) {
    init {
        require(tenureDays >= 0) { "Tenure cannot be negative" }
        require(grossCents >= 0 && refundCents >= 0 && chargebackCents >= 0) {
            "Observed amounts cannot be negative"
        }
        require(repaidPools >= 0)
        require(dailyGrossCents.all { it >= 0 }) { "A daily gross figure cannot be negative" }
        require(daysSinceDestinationChange == null || daysSinceDestinationChange >= 0)
    }

    /** Refunds and chargebacks as a share of gross, in basis points. Zero gross yields zero. */
    val refundRateBasisPoints: Int
        get() =
            if (grossCents <= 0) 0
            else
                share(
                    Math.addExact(refundCents, chargebackCents),
                    grossCents,
                )

    /**
     * Mean absolute deviation over the mean, in basis points: a dispersion measure that needs no
     * square root and stays in exact integer arithmetic. It is a descriptive statistic, not a
     * fitted model of anything.
     */
    val volatilityBasisPoints: Int
        get() {
            if (dailyGrossCents.size < 2) return 0
            val total =
                dailyGrossCents.fold(BigInteger.ZERO) { sum, it -> sum + BigInteger.valueOf(it) }
            if (total.signum() == 0) return 0
            val count = BigInteger.valueOf(dailyGrossCents.size.toLong())
            val mean = total / count
            if (mean.signum() == 0) return 0
            val deviation =
                dailyGrossCents.fold(BigInteger.ZERO) { sum, it ->
                    sum + (BigInteger.valueOf(it) - mean).abs()
                } / count
            return (deviation * BigInteger.valueOf(BASIS_POINTS_PER_WHOLE.toLong()) / mean)
                .min(BigInteger.valueOf(Int.MAX_VALUE.toLong()))
                .toInt()
        }
}

private fun share(part: Long, whole: Long): Int =
    (BigInteger.valueOf(part) * BigInteger.valueOf(BASIS_POINTS_PER_WHOLE.toLong()) /
            BigInteger.valueOf(whole))
        .min(BigInteger.valueOf(Int.MAX_VALUE.toLong()))
        .toInt()

/** One named contribution to the advance rate, so a rate can always be explained. */
data class RateFactor(val code: String, val deltaBasisPoints: Int, val explanation: String)

data class AdvanceRateDecision(
    val basisPoints: Int,
    val baseBasisPoints: Int,
    val factors: List<RateFactor>,
    val refundRateBasisPoints: Int,
    val volatilityBasisPoints: Int,
    val tenureDays: Int,
) {
    /** Every factor that moved the rate, in the order applied. */
    val explanation: String
        get() =
            if (factors.isEmpty())
                "No adjustment applied; the base rate stands at ${percent(baseBasisPoints)}%."
            else
                "Base ${percent(baseBasisPoints)}% adjusted to ${percent(basisPoints)}% by: " +
                    factors.joinToString("; ") { "${it.code} ${signed(it.deltaBasisPoints)}" }
}

private fun percent(basisPoints: Int) = "%.2f".format(basisPoints / 100.0)

private fun signed(basisPoints: Int) =
    (if (basisPoints >= 0) "+" else "") + "%.2f".format(basisPoints / 100.0) + "pp"

/**
 * A threshold band. [atOrAbove] is inclusive, so bands can be listed from mildest to harshest and
 * the last matching one wins.
 */
data class Band(val atOrAbove: Int, val deltaBasisPoints: Int, val label: String)

/**
 * Illustrative underwriting policy, versioned separately from commercial terms and from the review
 * thresholds in `capital/risk`.
 *
 * Every number here was chosen to make the mechanism legible, not fitted to outcomes. A real
 * advance rate needs observed repayment performance, a credit owner, and a model risk review. This
 * is explicitly none of those.
 */
data class UnderwritingPolicy(
    val version: String,
    val baseBasisPoints: Int = 8_000,
    val floorBasisPoints: Int = 3_000,
    val refundBands: List<Band> =
        listOf(
            Band(200, -500, "refunds above 2%"),
            Band(500, -1_500, "refunds above 5%"),
            Band(1_000, -3_000, "refunds above 10%"),
        ),
    val tenureBands: List<Band> =
        listOf(Band(0, -1_500, "under 30 days observed"), Band(30, -500, "under 90 days observed")),
    val establishedTenureDays: Int = 90,
    val volatilityBands: List<Band> = listOf(Band(7_500, -1_000, "daily sales highly dispersed")),
    /** Share of a developer's outstanding principal allowed to sit in one pool. */
    val concentrationCapBasisPoints: Int = 6_000,
    /**
     * Portfolio size at which the concentration cap starts to bind.
     *
     * Without a floor the cap is unusable: any first advance puts 100% of a zero portfolio behind
     * one pool, so every new developer would be refused. Below this size a single pool is the
     * expected shape, not a concentration to correct.
     */
    val concentrationAppliesAboveCents: Long = 1_000_000,
    /** New principal per rolling window, as a multiple of that window's observed gross. */
    val velocityMultiple: Int = 1,
    val velocityWindowDays: Int = 30,
    /** Extra credit granted per pool repaid in full, as a share of the base limit. */
    val stepUpBasisPointsPerRepaidPool: Int = 1_000,
    val stepUpCapBasisPoints: Int = 5_000,
) {
    init {
        require(version.isNotBlank())
        require(baseBasisPoints in 1..BASIS_POINTS_PER_WHOLE)
        require(floorBasisPoints in 0..baseBasisPoints) {
            "The floor rate cannot exceed the base rate"
        }
        require(concentrationCapBasisPoints in 1..BASIS_POINTS_PER_WHOLE)
        require(concentrationAppliesAboveCents >= 0)
        require(velocityMultiple >= 1 && velocityWindowDays >= 1)
        require(stepUpBasisPointsPerRepaidPool >= 0 && stepUpCapBasisPoints >= 0)
    }

    companion object {
        val ILLUSTRATIVE_V1 = UnderwritingPolicy("illustrative-underwriting-v1")
    }
}

/**
 * Derives an advance rate from what we have actually observed, replacing a flat rate for every
 * developer.
 *
 * Adjustments are additive in basis points and floored, never multiplied, so the worst case is
 * bounded and each contribution stays separately visible. The rate never rises above the base: good
 * history removes penalties rather than earning leverage.
 */
fun advanceRate(
    observation: Observation,
    policy: UnderwritingPolicy = UnderwritingPolicy.ILLUSTRATIVE_V1,
): AdvanceRateDecision {
    val factors = mutableListOf<RateFactor>()

    matchingBand(policy.refundBands, observation.refundRateBasisPoints)?.let {
        factors +=
            RateFactor(
                "REFUND_RATE",
                it.deltaBasisPoints,
                "Observed refunds and chargebacks are ${percent(observation.refundRateBasisPoints)}% of gross (${it.label}).",
            )
    }

    if (observation.tenureDays < policy.establishedTenureDays)
        matchingBand(policy.tenureBands, observation.tenureDays)?.let {
            factors +=
                RateFactor(
                    "SHORT_HISTORY",
                    it.deltaBasisPoints,
                    "Only ${observation.tenureDays} days of history are observed (${it.label}).",
                )
        }

    matchingBand(policy.volatilityBands, observation.volatilityBasisPoints)?.let {
        factors +=
            RateFactor(
                "VOLATILE_SALES",
                it.deltaBasisPoints,
                "Daily gross deviates from its own mean by ${percent(observation.volatilityBasisPoints)}% (${it.label}).",
            )
    }

    val unbounded = factors.fold(policy.baseBasisPoints) { rate, it -> rate + it.deltaBasisPoints }
    val bounded = unbounded.coerceIn(policy.floorBasisPoints, policy.baseBasisPoints)
    if (unbounded < policy.floorBasisPoints)
        factors +=
            RateFactor(
                "FLOOR_APPLIED",
                policy.floorBasisPoints - unbounded,
                "Adjustments reached ${percent(unbounded)}%, below the ${percent(policy.floorBasisPoints)}% floor.",
            )
    return AdvanceRateDecision(
        bounded,
        policy.baseBasisPoints,
        factors,
        observation.refundRateBasisPoints,
        observation.volatilityBasisPoints,
        observation.tenureDays,
    )
}

/**
 * The band for this value: the highest threshold it has reached, independent of list order.
 *
 * Bands partition a range, so exactly one applies. Selecting by harshest delta instead would be
 * wrong for tenure, where a lower value is the worse one.
 */
private fun matchingBand(bands: List<Band>, value: Int): Band? =
    bands.filter { value >= it.atOrAbove }.maxByOrNull { it.atOrAbove }

data class Constraint(val code: String, val permittedCents: Long, val explanation: String)

data class ExposureAssessment(
    val requestedCents: Long,
    val permittedCents: Long,
    val constraints: List<Constraint>,
) {
    val binding: Constraint?
        get() = constraints.minByOrNull { it.permittedCents }

    val explanation: String
        get() =
            when {
                constraints.isEmpty() -> "No portfolio constraint applied."
                permittedCents >= requestedCents ->
                    "Every portfolio constraint permits the requested amount; the tightest is " +
                        "${binding!!.code} at ${binding!!.permittedCents} cents."
                else ->
                    "Limited to $permittedCents cents by ${binding!!.code}. ${binding!!.explanation}"
            }
}

/**
 * Portfolio-level caps applied after per-pool policy capacity, never instead of it.
 *
 * These express risks a single pool's arithmetic cannot see: too much exposure behind one
 * receivable, and funding running ahead of observed sales. Each cap is reported with what it would
 * permit, so the binding one is identifiable rather than buried in a single number.
 */
fun assessExposure(
    requestedCents: Long,
    poolOutstandingCents: Long,
    totalOutstandingCents: Long,
    fundedInWindowCents: Long,
    observation: Observation,
    policy: UnderwritingPolicy = UnderwritingPolicy.ILLUSTRATIVE_V1,
): ExposureAssessment {
    require(requestedCents >= 0 && poolOutstandingCents >= 0 && totalOutstandingCents >= 0)
    require(fundedInWindowCents >= 0)
    val constraints = mutableListOf<Constraint>()

    // Concentration: after funding, this pool may hold at most the capped share of the total.
    // Solving share * (total + x) >= poolOutstanding + x for x gives the permitted increment.
    val cap = BigInteger.valueOf(policy.concentrationCapBasisPoints.toLong())
    val whole = BigInteger.valueOf(BASIS_POINTS_PER_WHOLE.toLong())
    val numerator =
        cap * BigInteger.valueOf(totalOutstandingCents) -
            whole * BigInteger.valueOf(poolOutstandingCents)
    val denominator = whole - cap
    if (totalOutstandingCents < policy.concentrationAppliesAboveCents)
        constraints +=
            Constraint(
                "CONCENTRATION",
                requestedCents,
                "Outstanding principal of $totalOutstandingCents cents is below the " +
                    "${policy.concentrationAppliesAboveCents} cent size at which the concentration cap binds.",
            )
    else {
        val concentration =
            if (denominator.signum() <= 0) requestedCents
            else (numerator / denominator).max(BigInteger.ZERO).longValueExact()
        constraints +=
            Constraint(
                "CONCENTRATION",
                concentration,
                "At most ${percent(policy.concentrationCapBasisPoints)}% of outstanding principal may sit " +
                    "behind one receivable pool.",
            )
    }

    val velocityAllowance =
        BigInteger.valueOf(observation.grossCents) *
            BigInteger.valueOf(policy.velocityMultiple.toLong())
    val velocity =
        (velocityAllowance - BigInteger.valueOf(fundedInWindowCents))
            .max(BigInteger.ZERO)
            .longValueExact()
    constraints +=
        Constraint(
            "VELOCITY",
            velocity,
            "New principal over ${policy.velocityWindowDays} days may not exceed " +
                "${policy.velocityMultiple}× the ${observation.grossCents} cents of gross observed in that window.",
        )

    return ExposureAssessment(
        requestedCents,
        minOf(requestedCents, constraints.minOf { it.permittedCents }),
        constraints,
    )
}

/**
 * Extra credit earned by pools that repaid in full through store proceeds.
 *
 * Deliberately capped and deliberately not automatic in the other direction: a repaid pool raises
 * the ceiling, while a loss does not lower it here, because reducing a committed limit is a
 * decision with contractual consequences rather than an arithmetic result.
 */
fun stepUpLimit(
    baseLimitCents: Long,
    observation: Observation,
    policy: UnderwritingPolicy = UnderwritingPolicy.ILLUSTRATIVE_V1,
): Long {
    require(baseLimitCents >= 0)
    val earned =
        minOf(
            Math.multiplyExact(
                observation.repaidPools.toLong(),
                policy.stepUpBasisPointsPerRepaidPool.toLong(),
            ),
            policy.stepUpCapBasisPoints.toLong(),
        )
    val bonus =
        (BigInteger.valueOf(baseLimitCents) * BigInteger.valueOf(earned) /
                BigInteger.valueOf(BASIS_POINTS_PER_WHOLE.toLong()))
            .longValueExact()
    return Math.addExact(baseLimitCents, bonus)
}
