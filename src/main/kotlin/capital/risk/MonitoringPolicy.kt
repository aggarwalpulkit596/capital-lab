package capital.risk

import capital.policy.BASIS_POINTS_PER_WHOLE
import java.math.BigDecimal

/** Illustrative review thresholds, separately versioned from the commercial payout terms. */
data class MonitoringPolicy(
    val version: String,
    val cancellationBasisPoints: Int,
    val velocityMultiple: Int,
    val baselineObservations: Int,
) {
    init {
        require(version.isNotBlank())
        require(cancellationBasisPoints in 1..BASIS_POINTS_PER_WHOLE)
        require(velocityMultiple >= 2)
        require(baselineObservations in 1..365)
    }

    val cancellationPercent: String
        get() =
            BigDecimal(cancellationBasisPoints)
                .movePointLeft(2)
                .stripTrailingZeros()
                .toPlainString()

    companion object {
        val DEMO_V1 = MonitoringPolicy("illustrative-review-v1", 1_000, 3, 7)
    }
}
