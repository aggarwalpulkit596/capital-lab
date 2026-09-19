package capital.risk

import capital.policy.BASIS_POINTS_PER_WHOLE
import java.math.BigInteger

data class Signal(
    val code: String,
    val component: String,
    val severity: String,
    val explanation: String,
)

data class RiskInputs(
    val grossCents: Long,
    val cancellationCents: Long,
    val baselineGrossCents: Long?,
    val baselineDays: Int,
)

/** Illustrative review rules, not a calibrated credit model or a fraud classifier. */
fun monitor(input: RiskInputs, policy: MonitoringPolicy = MonitoringPolicy.DEMO_V1): List<Signal> {
    require(input.grossCents >= 0 && input.cancellationCents >= 0 && input.baselineDays >= 0)
    require(input.baselineGrossCents == null || input.baselineGrossCents >= 0)
    fun times(value: Long, multiplier: Long) =
        BigInteger.valueOf(value) * BigInteger.valueOf(multiplier)
    return buildList {
        if (
            input.cancellationCents > 0 &&
                times(input.cancellationCents, BASIS_POINTS_PER_WHOLE.toLong()) >=
                    times(input.grossCents, policy.cancellationBasisPoints.toLong())
        )
            add(
                Signal(
                    "HIGH_CANCELLATIONS",
                    "Risk monitoring",
                    "HOLD",
                    "Cancellations are at least ${policy.cancellationPercent}% of gross sales. Pause for review; this does not establish fraud.",
                )
            )
        if (
            input.baselineDays >= policy.baselineObservations &&
                input.baselineGrossCents != null &&
                input.baselineGrossCents > 0
        ) {
            if (
                BigInteger.valueOf(input.grossCents) >=
                    times(input.baselineGrossCents, policy.velocityMultiple.toLong())
            )
                add(
                    Signal(
                        "REVENUE_SPIKE",
                        "Fraud review",
                        "HOLD",
                        "Gross sales are at least ${policy.velocityMultiple}× the preceding ${policy.baselineObservations} observed days' average. A spike is a review signal, not a fraud verdict.",
                    )
                )
        } else
            add(
                Signal(
                    "LIMITED_HISTORY",
                    "Underwriting",
                    "INFO",
                    "Fewer than ${policy.baselineObservations} prior observed days, or a zero baseline. The velocity rule cannot evaluate this period.",
                )
            )
    }
}
