package capital.ingestion

import capital.risk.MonitoringPolicy
import capital.risk.RiskInputs
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.math.RoundingMode

object PublicDataset {
    val json: JsonObject =
        JsonParser.parseString(
                checkNotNull(javaClass.getResource("/data/uci-retail-daily.json")).readText()
            )
            .asJsonObject
    val days: List<JsonObject> = json.getAsJsonArray("days").map { it.asJsonObject }

    fun day(date: String): JsonObject =
        days.singleOrNull { it["date"].asString == date }
            ?: throw IllegalArgumentException("Choose an available dataset date")

    /**
     * Synthetic replay only: fixed illustrative USD/GBP rate of 1.25; never presented as historical
     * FX.
     */
    private val illustrativeUsdPerGbp = BigDecimal("1.25")

    fun toDemoUsd(pence: Long): Long =
        BigDecimal(pence)
            .multiply(illustrativeUsdPerGbp)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()

    fun inputs(date: String, policy: MonitoringPolicy = MonitoringPolicy.DEMO_V1): RiskInputs {
        val index = days.indexOfFirst { it["date"].asString == date }
        require(index >= 0) { "Choose an available dataset date" }
        val prior = days.subList((index - policy.baselineObservations).coerceAtLeast(0), index)
        val baseline =
            if (prior.isEmpty()) null else prior.sumOf { it["grossMinor"].asLong } / prior.size
        return RiskInputs(
            toDemoUsd(days[index]["grossMinor"].asLong),
            toDemoUsd(days[index]["cancellationsMinor"].asLong),
            baseline?.let(::toDemoUsd),
            prior.size,
        )
    }
}
