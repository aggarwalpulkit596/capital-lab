package capital.settlement

import capital.payments.Database
import capital.payments.rows
import capital.payments.update
import capital.policy.FinancialTerms
import java.sql.Connection
import java.time.Clock
import java.util.UUID

/**
 * Handles a pool's expected proceeds falling after we have already advanced against them.
 *
 * This is the characteristic risk of advancing against subscription receivables: refunds and
 * chargebacks arrive after funding, so the receivable shrinks while the advance does not. Reducing
 * a limit never retrieves money already advanced. What it can do is state honestly that some
 * principal is no longer expected to arrive through store proceeds.
 *
 * That principal is reclassified, not forgiven: it moves out of the pool's outstanding balance and
 * into a recovery obligation collectible from the developer. The developer's total outstanding is
 * deliberately unchanged, because they still owe exactly the same amount — only the route by which
 * we expect to be repaid has changed.
 */
class ProceedsRevisionService(
    private val database: Database,
    private val clock: Clock,
    private val terms: FinancialTerms = FinancialTerms.EARLY_PAYOUTS_V1,
) {

    fun revise(revision: ProceedsRevision): RevisionResult {
        if (revision.id.isBlank())
            return rejected("A revision id is required so the same refund cannot be applied twice.")
        if (revision.reductionCents <= 0)
            return rejected(
                "A revision must reduce proceeds by a positive amount. Use a new advance for an increase."
            )

        return database.transaction { connection ->
            connection.rows("SELECT cash_cents FROM treasury WHERE id=1 FOR UPDATE") {}

            replayOf(connection, revision)?.let {
                return@transaction it
            }

            val pool =
                connection
                    .rows(
                        """SELECT p.developer_id,p.net_proceeds_cents,p.outstanding_cents,p.reserved_cents,d.limit_cents
                            FROM pools p JOIN developers d ON d.id=p.developer_id WHERE p.id=? FOR UPDATE OF p""",
                        revision.poolId,
                    ) {
                        Pool(
                            it.getString(1),
                            it.getLong(2),
                            it.getLong(3),
                            it.getLong(4),
                            it.getLong(5),
                        )
                    }
                    .singleOrNull()
                    ?: return@transaction rejected(
                        "Unknown pool ${revision.poolId}. Nothing was posted."
                    )
            connection.rows("SELECT id FROM developers WHERE id=? FOR UPDATE", pool.developerId) {}

            val before = pool.netProceeds
            val after = before - revision.reductionCents
            // Proceeds may legitimately go negative when refunds exceed the period's sales.
            // The advance limit floors at zero; the register keeps the true figure.
            val limit = minOf(terms.advanceLimit(after.coerceAtLeast(0)), pool.developerLimit)
            val excess = (pool.outstanding - limit).coerceAtLeast(0)

            connection.update(
                "UPDATE pools SET net_proceeds_cents=? WHERE id=?",
                after,
                revision.poolId,
            )
            connection.update(
                """INSERT INTO proceeds_revisions(id,pool_id,reason,reduction_cents,proceeds_before_cents,
                    proceeds_after_cents,reclassified_cents,created_at) VALUES (?,?,?,?,?,?,?,?)""",
                revision.id,
                revision.poolId,
                revision.reason.name,
                revision.reductionCents,
                before,
                after,
                excess,
                clock.instant(),
            )

            if (excess == 0L)
                return@transaction RevisionResult(
                    RevisionStatus.WITHIN_LIMIT,
                    before,
                    after,
                    limit,
                    pool.outstanding,
                    0,
                    openRecovery(connection, revision.poolId),
                    "Expected proceeds fell by ${revision.reductionCents} cents. Outstanding principal of " +
                        "${pool.outstanding} cents still sits within the revised limit of $limit cents, so nothing was reclassified.",
                )

            connection.update(
                "UPDATE pools SET outstanding_cents=outstanding_cents-? WHERE id=?",
                excess,
                revision.poolId,
            )
            connection.update(
                """INSERT INTO recovery_obligations(pool_id,developer_id,open_cents,lifetime_cents) VALUES (?,?,?,?)
                    ON CONFLICT (pool_id) DO UPDATE SET open_cents=recovery_obligations.open_cents+EXCLUDED.open_cents,
                    lifetime_cents=recovery_obligations.lifetime_cents+EXCLUDED.lifetime_cents""",
                revision.poolId,
                pool.developerId,
                excess,
                excess,
            )
            // Reclassification only. No cash moved, so this posts between two receivable accounts.
            val journal = UUID.randomUUID()
            connection.update(
                "INSERT INTO settlement_journals(id,kind,reference,posting_key,created_at) VALUES (?,'REVISION',?,?,?)",
                journal,
                revision.id,
                "revision_${revision.id}",
                clock.instant(),
            )
            entry(connection, journal, "RECOVERY_RECEIVABLE", "DEBIT", excess, revision.poolId)
            entry(connection, journal, "ADVANCE_RECEIVABLE", "CREDIT", excess, revision.poolId)

            RevisionResult(
                RevisionStatus.RECLASSIFIED,
                before,
                after,
                limit,
                pool.outstanding - excess,
                excess,
                openRecovery(connection, revision.poolId),
                "${revision.reason.name} reduced expected proceeds by ${revision.reductionCents} cents. " +
                    "Outstanding principal exceeded the revised limit of $limit cents by $excess cents, which moved " +
                    "to a recovery obligation collectible from the developer. The developer's total outstanding is unchanged; " +
                    "money already advanced was not retrieved by this posting.",
            )
        }
    }

    private data class Pool(
        val developerId: String,
        val netProceeds: Long,
        val outstanding: Long,
        val reserved: Long,
        val developerLimit: Long,
    )

    private fun replayOf(connection: Connection, revision: ProceedsRevision): RevisionResult? {
        val existing =
            connection
                .rows(
                    """SELECT pool_id,reduction_cents,proceeds_before_cents,proceeds_after_cents,reclassified_cents
                        FROM proceeds_revisions WHERE id=?""",
                    revision.id,
                ) {
                    listOf(
                        it.getString(1),
                        it.getLong(2).toString(),
                        it.getLong(3).toString(),
                        it.getLong(4).toString(),
                        it.getLong(5).toString(),
                    )
                }
                .singleOrNull() ?: return null
        check(existing[0] == revision.poolId && existing[1] == revision.reductionCents.toString()) {
            "Revision ${revision.id} was already applied to pool ${existing[0]} for ${existing[1]} cents"
        }
        return RevisionResult(
            RevisionStatus.ALREADY_APPLIED,
            existing[2].toLong(),
            existing[3].toLong(),
            0,
            0,
            existing[4].toLong(),
            openRecovery(connection, revision.poolId),
            "Revision ${revision.id} was already applied. The repeat had no additional effect.",
        )
    }

    private fun openRecovery(connection: Connection, poolId: String): Long =
        connection
            .rows("SELECT open_cents FROM recovery_obligations WHERE pool_id=?", poolId) {
                it.getLong(1)
            }
            .singleOrNull() ?: 0L

    private fun rejected(explanation: String) =
        RevisionResult(RevisionStatus.REJECTED, 0, 0, 0, 0, 0, 0, explanation)

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
