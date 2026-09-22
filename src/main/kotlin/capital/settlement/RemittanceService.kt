package capital.settlement

import capital.payments.Database
import capital.payments.rows
import capital.payments.update
import java.sql.Connection
import java.time.Clock
import java.util.UUID

/**
 * Applies money actually received from a store against the pools it covers.
 *
 * This replaces the earlier one-final-receipt-per-pool bound. A remittance may cover many pools and
 * may be partial: a pool stays open and keeps its outstanding principal until a line marked final
 * arrives, or until principal reaches zero and the store declares the pool finished.
 *
 * Per pool the waterfall is fixed and ordered, so the same remittance always allocates the same
 * way:
 * 1. open recovery obligations for that pool, because that principal is already known not to be
 *    coming back through store proceeds;
 * 2. outstanding advance principal;
 * 3. whatever remains becomes residual payable to the developer.
 *
 * Cash that names no known pool is never silently absorbed. It is held as unapplied and reported.
 */
class RemittanceService(private val database: Database, private val clock: Clock) {

    fun apply(remittance: Remittance): RemittanceResult {
        val rejection = validate(remittance)
        if (rejection != null)
            return RemittanceResult(
                RemittanceStatus.REJECTED,
                remittance.id,
                0,
                0,
                listOf(),
                rejection,
            )

        return database.transaction { connection ->
            // Same lock order as the funding path: treasury, then developer, then pools by id.
            connection.rows("SELECT cash_cents FROM treasury WHERE id=1 FOR UPDATE") {}
            val developerExists =
                connection
                    .rows(
                        "SELECT id FROM developers WHERE id=? FOR UPDATE",
                        remittance.developerId,
                    ) {
                        it.getString(1)
                    }
                    .singleOrNull()
            if (developerExists == null)
                return@transaction RemittanceResult(
                    RemittanceStatus.REJECTED,
                    remittance.id,
                    0,
                    0,
                    listOf(),
                    "Unknown developer ${remittance.developerId}. Nothing was posted.",
                )

            replayOf(connection, remittance)?.let {
                return@transaction it
            }

            val allocations = mutableListOf<LineAllocation>()
            var unapplied = 0L
            // Deterministic pool order keeps concurrent remittances from deadlocking.
            for (line in remittance.lines.sortedBy { it.poolId }) {
                val pool = lockPool(connection, line.poolId, remittance.developerId)
                if (pool == null) {
                    unapplied += line.cents
                    continue
                }
                allocations += allocate(connection, remittance, line, pool)
            }

            val applied = remittance.receivedCents - unapplied
            connection.update(
                """INSERT INTO remittances(id,store,developer_id,received_cents,currency,received_at,
                    applied_cents,unapplied_cents,created_at) VALUES (?,?,?,?,?,?,?,?,?)""",
                remittance.id,
                remittance.store,
                remittance.developerId,
                remittance.receivedCents,
                remittance.currency,
                remittance.receivedAt,
                applied,
                unapplied,
                clock.instant(),
            )
            allocations.forEach {
                connection.update(
                    """INSERT INTO remittance_lines(remittance_id,pool_id,line_cents,recovery_cents,
                        principal_cents,residual_cents,final_line) VALUES (?,?,?,?,?,?,?)""",
                    remittance.id,
                    it.poolId,
                    it.lineCents,
                    it.recoveryCents,
                    it.principalCents,
                    it.residualCents,
                    it.poolClosed,
                )
            }
            post(connection, remittance, allocations, unapplied)

            val residual = allocations.sumOf { it.residualCents }
            if (residual > 0)
                connection.update(
                    "UPDATE developers SET payable_cents=payable_cents+? WHERE id=?",
                    residual,
                    remittance.developerId,
                )

            RemittanceResult(
                if (unapplied > 0) RemittanceStatus.PARTIALLY_APPLIED else RemittanceStatus.APPLIED,
                remittance.id,
                applied,
                unapplied,
                allocations,
                explain(allocations, unapplied),
            )
        }
    }

    private fun validate(remittance: Remittance): String? {
        if (remittance.id.isBlank()) return "Remittance id is required."
        if (remittance.currency != "USD")
            return "Only USD is supported; ${remittance.currency} was presented."
        if (remittance.receivedCents <= 0) return "A remittance must carry a positive amount."
        if (remittance.lines.isEmpty())
            return "A remittance must name at least one pool. Unattributed store cash is not applied."
        if (remittance.lines.any { it.cents <= 0 }) return "Every remittance line must be positive."
        if (remittance.lines.map { it.poolId }.toSet().size != remittance.lines.size)
            return "A pool may appear at most once per remittance."
        val total = remittance.lines.sumOf { it.cents }
        if (total != remittance.receivedCents)
            return "Lines total $total but the store sent ${remittance.receivedCents}. " +
                "The difference is unexplained, so nothing was posted."
        return null
    }

    /** A repeat of an applied remittance must report the original outcome, not allocate again. */
    private fun replayOf(connection: Connection, remittance: Remittance): RemittanceResult? {
        val header =
            connection
                .rows(
                    "SELECT received_cents,applied_cents,unapplied_cents FROM remittances WHERE id=?",
                    remittance.id,
                ) {
                    Triple(it.getLong(1), it.getLong(2), it.getLong(3))
                }
                .singleOrNull() ?: return null
        check(header.first == remittance.receivedCents) {
            "Remittance ${remittance.id} was already applied for ${header.first} cents; " +
                "${remittance.receivedCents} cents was presented under the same id"
        }
        val lines =
            connection.rows(
                """SELECT pool_id,line_cents,recovery_cents,principal_cents,residual_cents,final_line
                    FROM remittance_lines WHERE remittance_id=? ORDER BY pool_id""",
                remittance.id,
            ) {
                LineAllocation(
                    it.getString(1),
                    it.getLong(2),
                    it.getLong(3),
                    it.getLong(4),
                    it.getLong(5),
                    it.getBoolean(6),
                    outstandingAfterCents = -1,
                )
            }
        return RemittanceResult(
            RemittanceStatus.ALREADY_APPLIED,
            remittance.id,
            header.second,
            header.third,
            lines,
            "Remittance ${remittance.id} was already applied. The repeat had no additional effect.",
        )
    }

    private data class PoolState(val outstanding: Long, val settled: Long, val closed: Boolean)

    private fun lockPool(connection: Connection, poolId: String, developerId: String): PoolState? =
        connection
            .rows(
                "SELECT outstanding_cents,settled_proceeds_cents,closed FROM pools WHERE id=? AND developer_id=? FOR UPDATE",
                poolId,
                developerId,
            ) {
                PoolState(it.getLong(1), it.getLong(2), it.getBoolean(3))
            }
            .singleOrNull()

    private fun allocate(
        connection: Connection,
        remittance: Remittance,
        line: RemittanceLine,
        pool: PoolState,
    ): LineAllocation {
        var available = line.cents

        val openRecovery =
            connection
                .rows(
                    "SELECT open_cents FROM recovery_obligations WHERE pool_id=? FOR UPDATE",
                    line.poolId,
                ) {
                    it.getLong(1)
                }
                .singleOrNull() ?: 0L
        val recovery = minOf(openRecovery, available)
        available -= recovery

        val principal = minOf(pool.outstanding, available)
        available -= principal

        val residual = available
        val outstandingAfter = pool.outstanding - principal
        // A pool closes only when the store says this is the last money for it. Reaching zero
        // principal early does not close it; more proceeds may still arrive.
        val close = line.finalLine && !pool.closed

        if (recovery > 0) {
            connection.update(
                "UPDATE recovery_obligations SET open_cents=open_cents-? WHERE pool_id=?",
                recovery,
                line.poolId,
            )
            connection.update(
                "UPDATE developers SET outstanding_cents=outstanding_cents-? WHERE id=?",
                recovery,
                remittance.developerId,
            )
        }
        if (principal > 0)
            connection.update(
                "UPDATE developers SET outstanding_cents=outstanding_cents-? WHERE id=?",
                principal,
                remittance.developerId,
            )
        connection.update(
            "UPDATE pools SET outstanding_cents=outstanding_cents-?,settled_proceeds_cents=settled_proceeds_cents+?,closed=? WHERE id=?",
            principal,
            line.cents,
            pool.closed || close,
            line.poolId,
        )
        return LineAllocation(
            line.poolId,
            line.cents,
            recovery,
            principal,
            residual,
            pool.closed || close,
            outstandingAfter,
        )
    }

    /**
     * One balanced settlement journal per remittance. Collected cash is debited; every cent is
     * credited to the obligation it discharged, so the journal explains where the money went.
     */
    private fun post(
        connection: Connection,
        remittance: Remittance,
        allocations: List<LineAllocation>,
        unapplied: Long,
    ) {
        val journal = UUID.randomUUID()
        connection.update(
            "INSERT INTO settlement_journals(id,kind,reference,posting_key,created_at) VALUES (?,'REMITTANCE',?,?,?)",
            journal,
            remittance.id,
            "remittance_${remittance.id}",
            clock.instant(),
        )
        entry(connection, journal, "COLLECTION_CASH", "DEBIT", remittance.receivedCents, null)
        allocations.forEach {
            if (it.recoveryCents > 0)
                entry(
                    connection,
                    journal,
                    "RECOVERY_RECEIVABLE",
                    "CREDIT",
                    it.recoveryCents,
                    it.poolId,
                )
            if (it.principalCents > 0)
                entry(
                    connection,
                    journal,
                    "ADVANCE_RECEIVABLE",
                    "CREDIT",
                    it.principalCents,
                    it.poolId,
                )
            if (it.residualCents > 0)
                entry(
                    connection,
                    journal,
                    "DEVELOPER_PAYABLE",
                    "CREDIT",
                    it.residualCents,
                    it.poolId,
                )
        }
        if (unapplied > 0) entry(connection, journal, "UNAPPLIED_CASH", "CREDIT", unapplied, null)
    }

    private fun explain(allocations: List<LineAllocation>, unapplied: Long): String {
        val recovery = allocations.sumOf { it.recoveryCents }
        val principal = allocations.sumOf { it.principalCents }
        val residual = allocations.sumOf { it.residualCents }
        val open = allocations.count { !it.poolClosed && it.outstandingAfterCents > 0 }
        return buildString {
            append("Applied across ${allocations.size} pool(s): ")
            append(
                "$recovery to recovery, $principal to principal, $residual to developer residual."
            )
            if (open > 0) append(" $open pool(s) keep outstanding principal and stay open.")
            if (unapplied > 0)
                append(
                    " $unapplied cents named no pool belonging to this developer and is held unapplied, not absorbed."
                )
        }
    }

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
