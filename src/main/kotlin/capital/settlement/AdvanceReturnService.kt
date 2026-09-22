package capital.settlement

import capital.payments.Database
import capital.payments.rows
import capital.payments.update
import java.time.Clock
import java.util.UUID

/**
 * Reverses an advance the bank settled and later returned.
 *
 * A return is a genuinely later fact, not a correction of the original one: the funding journal
 * stated truthfully that cash left on that day. So the original posting is never edited. A second,
 * balanced journal is appended with `kind='REVERSAL'`, and the schema permits exactly one of each
 * kind per advance, which makes a double reversal a constraint violation rather than a silent
 * second credit.
 *
 * Returns are refused once store proceeds have already repaid the principal. At that point the
 * money has been collected from a different source, and reversing would drive the pool's
 * outstanding balance below zero; the conflict needs a human, not an automatic posting.
 */
class AdvanceReturnService(private val database: Database, private val clock: Clock) {

    fun returnAdvance(advanceId: UUID, reason: String): ReturnResult {
        require(reason.isNotBlank()) { "A bank return must carry the provider's reason code" }
        return database.transaction { connection ->
            connection.rows("SELECT cash_cents FROM treasury WHERE id=1 FOR UPDATE") {}
            val advance =
                connection
                    .rows(
                        """SELECT developer_id,pool_id,principal_cents,fee_cents,cash_cents,state
                            FROM advances WHERE id=? FOR UPDATE""",
                        advanceId,
                    ) {
                        Advance(
                            it.getString(1),
                            it.getString(2),
                            it.getLong(3),
                            it.getLong(4),
                            it.getLong(5),
                            it.getString(6),
                        )
                    }
                    .singleOrNull()
                    ?: return@transaction ReturnResult(
                        ReturnStatus.REJECTED,
                        0,
                        0,
                        "Unknown advance $advanceId. Nothing was reversed.",
                    )

            if (advance.state == "RETURNED")
                return@transaction ReturnResult(
                    ReturnStatus.ALREADY_REVERSED,
                    advance.principal,
                    advance.cash,
                    "Advance $advanceId was already returned. The repeat had no additional effect.",
                )
            if (advance.state != "SETTLED")
                return@transaction ReturnResult(
                    ReturnStatus.REJECTED,
                    0,
                    0,
                    "Only a settled advance can be returned; $advanceId is ${advance.state}. " +
                        "An unsettled advance is cancelled or retried through the dispatch path instead.",
                )

            connection.rows(
                "SELECT id FROM developers WHERE id=? FOR UPDATE",
                advance.developerId,
            ) {}
            val outstanding =
                connection
                    .rows(
                        "SELECT outstanding_cents FROM pools WHERE id=? FOR UPDATE",
                        advance.poolId,
                    ) {
                        it.getLong(1)
                    }
                    .single()
            if (outstanding < advance.principal)
                return@transaction ReturnResult(
                    ReturnStatus.REJECTED,
                    0,
                    0,
                    "Pool ${advance.poolId} holds $outstanding cents of outstanding principal but the returned " +
                        "advance is ${advance.principal} cents. Store proceeds have already repaid part of it, so this " +
                        "return conflicts with a recorded collection and needs manual resolution.",
                )

            connection.update(
                "UPDATE treasury SET cash_cents=cash_cents+? WHERE id=1",
                advance.cash,
            )
            connection.update(
                "UPDATE developers SET outstanding_cents=outstanding_cents-? WHERE id=?",
                advance.principal,
                advance.developerId,
            )
            connection.update(
                "UPDATE pools SET outstanding_cents=outstanding_cents-?,funded_lifetime_cents=funded_lifetime_cents-? WHERE id=?",
                advance.principal,
                advance.principal,
                advance.poolId,
            )
            connection.update(
                "UPDATE advances SET state='RETURNED',lease_until=NULL,last_reason=? WHERE id=?",
                reason,
                advanceId,
            )
            connection.update("UPDATE outbox SET done=TRUE WHERE advance_id=?", advanceId)

            // Exact mirror of the funding journal, in the funding ledger where the original sits.
            val journal = UUID.randomUUID()
            connection.update(
                "INSERT INTO journals(id,advance_id,posting_key,kind,created_at) VALUES (?,?,?,'REVERSAL',?)",
                journal,
                advanceId,
                "reversal_$advanceId",
                clock.instant(),
            )
            entry(connection, journal, "ADVANCE_RECEIVABLE", "CREDIT", advance.principal)
            entry(connection, journal, "FUNDING_CASH", "DEBIT", advance.cash)
            if (advance.fee > 0) entry(connection, journal, "DEFERRED_FEE", "DEBIT", advance.fee)

            ReturnResult(
                ReturnStatus.REVERSED,
                advance.principal,
                advance.cash,
                "Bank returned the advance ($reason). A reversing journal was appended; the original funding " +
                    "posting is unchanged. ${advance.cash} cents of cash and ${advance.principal} cents of credit " +
                    "capacity were restored. No fee is retained on a returned advance.",
            )
        }
    }

    private data class Advance(
        val developerId: String,
        val poolId: String,
        val principal: Long,
        val fee: Long,
        val cash: Long,
        val state: String,
    )

    private fun entry(
        connection: java.sql.Connection,
        journal: UUID,
        account: String,
        side: String,
        cents: Long,
    ) {
        connection.update(
            "INSERT INTO ledger_entries(journal_id,account,currency,side,cents) VALUES (?,?,'USD',?,?)",
            journal,
            account,
            side,
            cents,
        )
    }
}
