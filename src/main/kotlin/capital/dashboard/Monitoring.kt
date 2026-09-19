package capital.dashboard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigInteger

data class Signal(val code: String, val component: String, val severity: String, val explanation: String)
data class RiskInputs(val grossCents: Long, val cancellationCents: Long, val baselineGrossCents: Long?, val baselineDays: Int)

/** Illustrative review rules, not a calibrated credit model or a fraud classifier. */
fun monitor(input: RiskInputs): List<Signal> {
    require(input.grossCents >= 0 && input.cancellationCents >= 0 && input.baselineDays >= 0)
    require(input.baselineGrossCents == null || input.baselineGrossCents >= 0)
    fun times(value: Long, multiplier: Long) = BigInteger.valueOf(value) * BigInteger.valueOf(multiplier)
    return buildList {
        if (input.cancellationCents > 0 && times(input.cancellationCents, 10) >= BigInteger.valueOf(input.grossCents))
            add(Signal("HIGH_CANCELLATIONS", "Risk monitoring", "HOLD", "Cancellations are at least 10% of gross sales. Pause for review; this does not establish fraud."))
        if (input.baselineDays >= 7 && input.baselineGrossCents != null && input.baselineGrossCents > 0) {
            if (BigInteger.valueOf(input.grossCents) >= times(input.baselineGrossCents, 3))
                add(Signal("REVENUE_SPIKE", "Fraud review", "HOLD", "Gross sales are at least 3× the preceding 7 observed days' average. A spike is a review signal, not a fraud verdict."))
        } else add(Signal("LIMITED_HISTORY", "Underwriting", "INFO", "Fewer than 7 prior observed days, or a zero baseline. The velocity rule cannot evaluate this period."))
    }
}

object PublicDataset {
    val json: JsonObject = JsonParser.parseString(checkNotNull(javaClass.getResource("/data/uci-retail-daily.json")).readText()).asJsonObject
    val days: List<JsonObject> = json.getAsJsonArray("days").map { it.asJsonObject }
    fun day(date: String): JsonObject = days.singleOrNull { it["date"].asString == date }
        ?: throw IllegalArgumentException("Choose an available dataset date")
    /** Synthetic replay only: fixed illustrative USD/GBP rate of 1.25; never presented as historical FX. */
    fun toDemoUsd(pence: Long): Long = (BigInteger.valueOf(pence) * BigInteger.valueOf(125) + if (pence >= 0) BigInteger.valueOf(50) else BigInteger.valueOf(-50))
        .divide(BigInteger.valueOf(100)).longValueExact()
    fun inputs(date: String): RiskInputs {
        val index = days.indexOfFirst { it["date"].asString == date }
        require(index >= 0) { "Choose an available dataset date" }
        val prior = days.subList((index - 7).coerceAtLeast(0), index)
        val baseline = if (prior.isEmpty()) null else prior.sumOf { it["grossMinor"].asLong } / prior.size
        return RiskInputs(toDemoUsd(days[index]["grossMinor"].asLong), toDemoUsd(days[index]["cancellationsMinor"].asLong), baseline?.let(::toDemoUsd), prior.size)
    }
}

data class PaymentEvidence(val key: String, val transferId: String?, val cashCents: Long, val currency: String, val state: String, val postedCashCents: Long)
data class StatementLine(val key: String, val transferId: String, val cents: Long, val currency: String)
data class ReconciliationRow(val key: String, val status: String, val expectedCents: Long?, val observedCents: Long?, val explanation: String)

/** Read-only match by stable identity, amount, currency, and posting. Never repairs history. */
fun reconcile(payments: List<PaymentEvidence>, statement: List<StatementLine>): List<ReconciliationRow> {
    val grouped = statement.groupBy { it.key }
    val paymentKeys = payments.map { it.key }.toSet()
    return payments.map { payment ->
        val lines = grouped[payment.key].orEmpty()
        val line = lines.singleOrNull()
        val status = when {
            lines.size > 1 -> "DUPLICATE_STATEMENT"
            line == null && payment.state == "SETTLED" -> "MISSING_BANK_DEBIT"
            line == null && payment.state in setOf("REJECTED", "CANCELED") -> "NO_CASH_EXPECTED"
            line == null -> "PENDING"
            payment.cashCents != line.cents || payment.currency != line.currency -> "AMOUNT_OR_CURRENCY_MISMATCH"
            payment.state != "SETTLED" -> "UNPOSTED_BANK_DEBIT"
            payment.transferId != line.transferId -> "REFERENCE_MISMATCH"
            payment.postedCashCents != line.cents -> "LEDGER_MISMATCH"
            else -> "MATCHED"
        }
        ReconciliationRow(payment.key, status, payment.cashCents, line?.cents, when (status) {
            "MATCHED" -> "Frozen payment, bank debit, and funding cash journal agree."
            "PENDING" -> "No confirmed debit yet; preserve any reservation."
            "NO_CASH_EXPECTED" -> "Canceled/rejected before funding; no bank debit."
            else -> "Exception requires evidence. No automatic adjustment or second payment."
        })
    } + statement.filter { it.key !in paymentKeys }.map {
        ReconciliationRow(it.key, "UNKNOWN_BANK_DEBIT", null, it.cents, "No local payment has this identity. Keep the exception open.")
    }
}
