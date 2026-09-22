package capital.settlement

import capital.payments.Database
import capital.payments.rows
import capital.payments.update
import java.sql.Connection
import java.time.Clock
import java.util.UUID

/**
 * Releases residual store proceeds back to the developer once their advance is repaid.
 *
 * Open recovery obligations are netted first, across every pool belonging to the developer, oldest
 * pool id first for determinism. Paying residual while the same developer owes reclassified
 * principal would hand back money we are simultaneously trying to collect.
 *
 * This records the release as a balanced posting and decrements the payable. Instructing the actual
 * outbound transfer is the dispatch path's job and is deliberately not performed here, so a
 * bookkeeping release is never mistaken for settled cash.
 */
class ResidualPayoutService(private val database: Database, private val clock: Clock) {

    fun release(payoutId: String, developerId: String): ResidualResult {
        if (payoutId.isBlank())
            return ResidualResult(
                ResidualStatus.REJECTED,
                0,
                0,
                0,
                "A payout id is required so the same release cannot be posted twice.",
            )
        return database.transaction { connection ->
            connection.rows("SELECT cash_cents FROM treasury WHERE id=1 FOR UPDATE") {}

            replayOf(connection, payoutId)?.let {
                return@transaction it
            }

            val payable =
                connection
                    .rows(
                        "SELECT payable_cents FROM developers WHERE id=? FOR UPDATE",
                        developerId,
                    ) {
                        it.getLong(1)
                    }
                    .singleOrNull()
                    ?: return@transaction ResidualResult(
                        ResidualStatus.REJECTED,
                        0,
                        0,
                        0,
                        "Unknown developer $developerId. Nothing was released.",
                    )
            if (payable == 0L)
                return@transaction ResidualResult(
                    ResidualStatus.NOTHING_PAYABLE,
                    0,
                    0,
                    0,
                    "No residual is payable to $developerId.",
                )

            var remaining = payable
            var recovered = 0L
            val obligations =
                connection.rows(
                    "SELECT pool_id,open_cents FROM recovery_obligations WHERE developer_id=? AND open_cents>0 ORDER BY pool_id FOR UPDATE",
                    developerId,
                ) {
                    it.getString(1) to it.getLong(2)
                }
            val applied = mutableListOf<Pair<String, Long>>()
            for ((poolId, open) in obligations) {
                if (remaining == 0L) break
                val take = minOf(open, remaining)
                remaining -= take
                recovered += take
                applied += poolId to take
                connection.update(
                    "UPDATE recovery_obligations SET open_cents=open_cents-? WHERE pool_id=?",
                    take,
                    poolId,
                )
            }
            if (recovered > 0)
                connection.update(
                    "UPDATE developers SET outstanding_cents=outstanding_cents-? WHERE id=?",
                    recovered,
                    developerId,
                )
            connection.update(
                "UPDATE developers SET payable_cents=payable_cents-? WHERE id=?",
                payable,
                developerId,
            )
            connection.update(
                """INSERT INTO residual_payouts(id,developer_id,requested_cents,recovered_cents,paid_cents,created_at)
                    VALUES (?,?,?,?,?,?)""",
                payoutId,
                developerId,
                payable,
                recovered,
                remaining,
                clock.instant(),
            )

            val journal = UUID.randomUUID()
            connection.update(
                "INSERT INTO settlement_journals(id,kind,reference,posting_key,created_at) VALUES (?,'RESIDUAL_PAYOUT',?,?,?)",
                journal,
                payoutId,
                "residual_$payoutId",
                clock.instant(),
            )
            entry(connection, journal, "DEVELOPER_PAYABLE", "DEBIT", payable, null)
            applied.forEach { (poolId, take) ->
                entry(connection, journal, "RECOVERY_RECEIVABLE", "CREDIT", take, poolId)
            }
            if (remaining > 0)
                entry(connection, journal, "COLLECTION_CASH", "CREDIT", remaining, null)

            ResidualResult(
                if (remaining == 0L) ResidualStatus.FULLY_RECOVERED else ResidualStatus.PAID,
                payable,
                recovered,
                remaining,
                if (remaining == 0L)
                    "All $payable cents of residual went to open recovery across ${applied.size} pool(s). " +
                        "Nothing was released to the developer."
                else
                    "$payable cents of residual: $recovered cents netted against open recovery, $remaining cents " +
                        "released to $developerId. Release is a posting; the outbound transfer is a separate dispatch.",
            )
        }
    }

    private fun replayOf(connection: Connection, payoutId: String): ResidualResult? =
        connection
            .rows(
                "SELECT requested_cents,recovered_cents,paid_cents FROM residual_payouts WHERE id=?",
                payoutId,
            ) {
                ResidualResult(
                    ResidualStatus.ALREADY_PAID,
                    it.getLong(1),
                    it.getLong(2),
                    it.getLong(3),
                    "Residual payout $payoutId was already posted. The repeat had no additional effect.",
                )
            }
            .singleOrNull()

    private fun entry(
        connection: Connection,
        journal: UUID,
        account: String,
        side: String,
        cents: Long,
        poolId: String?,
    ) {
        connection.update(
            "INSERT INTO settlement_entries(journal_id,account,currency,side,cents,pool_id) VALUES (?,?,'USD',?,?,?)",
            journal,
            account,
            side,
            cents,
            poolId,
        )
    }
}
