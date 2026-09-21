package capital.reconciliation

data class PaymentEvidence(
    val key: String,
    val transferId: String?,
    val cashCents: Long,
    val currency: String,
    val state: String,
    val postedCashCents: Long,
)

data class StatementLine(
    val key: String,
    val transferId: String,
    val cents: Long,
    val currency: String,
)

data class ReconciliationRow(
    val key: String,
    val status: String,
    val expectedCents: Long?,
    val observedCents: Long?,
    val explanation: String,
)

/** Read-only match by stable identity, amount, currency, and posting. Never repairs history. */
fun reconcile(
    payments: List<PaymentEvidence>,
    statement: List<StatementLine>,
): List<ReconciliationRow> {
    val grouped = statement.groupBy { it.key }
    val paymentKeys = payments.map { it.key }.toSet()
    return payments.map { payment ->
        val lines = grouped[payment.key].orEmpty()
        val line = lines.singleOrNull()
        val status =
            when {
                lines.size > 1 -> "DUPLICATE_STATEMENT"
                line == null && payment.state == "SETTLED" -> "MISSING_BANK_DEBIT"
                line == null && payment.state in setOf("REJECTED", "CANCELED") -> "NO_CASH_EXPECTED"
                line == null -> "PENDING"
                payment.cashCents != line.cents || payment.currency != line.currency ->
                    "AMOUNT_OR_CURRENCY_MISMATCH"
                payment.state != "SETTLED" -> "UNPOSTED_BANK_DEBIT"
                payment.transferId != line.transferId -> "REFERENCE_MISMATCH"
                payment.postedCashCents != line.cents -> "LEDGER_MISMATCH"
                else -> "MATCHED"
            }
        ReconciliationRow(
            payment.key,
            status,
            payment.cashCents,
            line?.cents,
            when (status) {
                "MATCHED" -> "Frozen payment, bank debit, and funding cash journal agree."
                "PENDING" -> "No confirmed debit yet; preserve any reservation."
                "NO_CASH_EXPECTED" -> "Canceled/rejected before funding; no bank debit."
                else -> "Exception requires evidence. No automatic adjustment or second payment."
            },
        )
    } +
        statement
            .filter { it.key !in paymentKeys }
            .map {
                ReconciliationRow(
                    it.key,
                    "UNKNOWN_BANK_DEBIT",
                    null,
                    it.cents,
                    "No local payment has this identity. Keep the exception open.",
                )
            }
}
