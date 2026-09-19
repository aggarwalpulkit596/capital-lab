package capital.collections

import capital.payments.Database
import capital.payments.number
import capital.payments.rows
import capital.payments.update

data class StoreReceipt(
    val id: String,
    val reportId: String,
    val poolId: String,
    val cents: Long,
    val currency: String = "USD",
)

data class FinalReport(
    val id: String,
    val poolId: String,
    val cents: Long,
    val currency: String = "USD",
)

data class CollectionResult(
    val status: String,
    val principalCents: Long,
    val residualCents: Long,
    val explanation: String,
)

/**
 * Bounded demo: one final report and one confirmed receipt per pool; no partial/aggregated
 * collection support.
 */
class CollectionService(private val database: Database) {
    fun allocate(receipt: StoreReceipt, report: FinalReport): CollectionResult {
        require(receipt.cents > 0 && report.cents > 0)
        if (
            receipt.reportId != report.id ||
                receipt.poolId != report.poolId ||
                receipt.currency != "USD" ||
                report.currency != "USD" ||
                receipt.cents != report.cents
        )
            return CollectionResult(
                "UNAPPLIED",
                0,
                0,
                "Receipt identity, currency, or amount differs from the final report. No repayment was posted.",
            )
        return database.transaction { c ->
            c.number("SELECT cash_cents FROM treasury WHERE id=1 FOR UPDATE")
            val owner =
                c.rows("SELECT developer_id FROM pools WHERE id=?", receipt.poolId) {
                        it.getString(1)
                    }
                    .single()
            c.number("SELECT outstanding_cents FROM developers WHERE id=? FOR UPDATE", owner)
            val pool =
                c.rows(
                        "SELECT outstanding_cents,reserved_cents,closed FROM pools WHERE id=? FOR UPDATE",
                        receipt.poolId,
                    ) {
                        Triple(it.getLong(1), it.getLong(2), it.getBoolean(3))
                    }
                    .single()
            val existing =
                c.rows(
                        "SELECT * FROM lab_collections WHERE receipt_id=? OR report_id=?",
                        receipt.id,
                        report.id,
                    ) {
                        listOf(
                            it.getString("receipt_id"),
                            it.getString("report_id"),
                            it.getString("pool_id"),
                            it.getLong("received_cents").toString(),
                            it.getLong("principal_cents").toString(),
                            it.getLong("residual_cents").toString(),
                        )
                    }
                    .singleOrNull()
            if (existing != null) {
                require(
                    existing.take(4) ==
                        listOf(receipt.id, report.id, receipt.poolId, receipt.cents.toString())
                ) {
                    "Collection identity conflict"
                }
                return@transaction CollectionResult(
                    "ALREADY_ALLOCATED",
                    existing[4].toLong(),
                    existing[5].toLong(),
                    "Duplicate receipt has no additional effect.",
                )
            }
            if (pool.second > 0)
                return@transaction CollectionResult(
                    "UNAPPLIED",
                    0,
                    0,
                    "Resolve pending advances before closing this pool.",
                )
            require(!pool.third) { "Final collection already closed this pool" }
            val principal = minOf(pool.first, receipt.cents)
            val residual = receipt.cents - principal
            c.update(
                "INSERT INTO lab_collections(receipt_id,report_id,pool_id,received_cents,principal_cents,residual_cents) VALUES (?,?,?,?,?,?)",
                receipt.id,
                report.id,
                receipt.poolId,
                receipt.cents,
                principal,
                residual,
            )
            c.update(
                "INSERT INTO lab_collection_entries(receipt_id,account,side,cents) VALUES (?,'COLLECTION_CASH','DEBIT',?)",
                receipt.id,
                receipt.cents,
            )
            if (principal > 0)
                c.update(
                    "INSERT INTO lab_collection_entries(receipt_id,account,side,cents) VALUES (?,'ADVANCE_RECEIVABLE','CREDIT',?)",
                    receipt.id,
                    principal,
                )
            if (residual > 0)
                c.update(
                    "INSERT INTO lab_collection_entries(receipt_id,account,side,cents) VALUES (?,'DEVELOPER_PAYABLE','CREDIT',?)",
                    receipt.id,
                    residual,
                )
            c.update(
                "UPDATE developers SET outstanding_cents=outstanding_cents-? WHERE id=?",
                principal,
                owner,
            )
            c.update(
                "UPDATE pools SET outstanding_cents=outstanding_cents-?,settled_proceeds_cents=settled_proceeds_cents+?,closed=TRUE WHERE id=?",
                principal,
                receipt.cents,
                receipt.poolId,
            )
            CollectionResult(
                if (principal < pool.first) "SHORTFALL" else "ALLOCATED",
                principal,
                residual,
                "Final pool closed; lifetime funding is unchanged. Residual remains payable; no sweep or residual payout was executed.",
            )
        }
    }
}
