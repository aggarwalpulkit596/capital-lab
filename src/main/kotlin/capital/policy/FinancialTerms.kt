package capital.policy

import capital.Policy
import java.math.BigInteger

/** Unit conversion is an invariant, not an operational setting. */
const val BASIS_POINTS_PER_WHOLE = 10_000

/** Exact cents arithmetic; never multiply money in floating point or in a bounded intermediate. */
fun applyBasisPoints(cents: Long, basisPoints: Int, roundHalfUp: Boolean = false): Long {
    require(cents >= 0 && basisPoints in 0..BASIS_POINTS_PER_WHOLE)
    val denominator = BigInteger.valueOf(BASIS_POINTS_PER_WHOLE.toLong())
    val numerator = BigInteger.valueOf(cents) * BigInteger.valueOf(basisPoints.toLong())
    val rounded = if (roundHalfUp) numerator + denominator / BigInteger.TWO else numerator
    return (rounded / denominator).longValueExact()
}

/**
 * Versioned commercial terms. Inject the same instance into quoting and authorization. Changing
 * these terms is a reviewed policy change, not an environment variable toggle.
 */
data class FinancialTerms(
    val version: String,
    val advanceBasisPoints: Int,
    val feeBasisPoints: Int,
    val allowedReportingLagDays: Int = 0,
) {
    init {
        require(version.isNotBlank())
        require(advanceBasisPoints in 1..BASIS_POINTS_PER_WHOLE)
        require(feeBasisPoints in 0 until BASIS_POINTS_PER_WHOLE)
        require(allowedReportingLagDays in 0..7)
    }

    fun quotePolicy(exposureCeilingCents: Long) =
        Policy(
            version,
            exposureCeilingCents,
            advanceBasisPoints,
            feeBasisPoints,
            allowedReportingLagDays,
        )

    fun advanceLimit(proceedsCents: Long) =
        applyBasisPoints(proceedsCents.coerceAtLeast(0), advanceBasisPoints)

    fun fee(principalCents: Long) =
        applyBasisPoints(principalCents, feeBasisPoints, roundHalfUp = true)

    companion object {
        val EARLY_PAYOUTS_V1 =
            FinancialTerms("early-payouts-v1", advanceBasisPoints = 8_000, feeBasisPoints = 250)
    }
}
