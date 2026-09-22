package capital.portfolio

import capital.payments.Database
import capital.payments.rows

data class PoolPosition(
    val poolId: String,
    val netProceedsCents: Long,
    val settledProceedsCents: Long,
    val fundedLifetimeCents: Long,
    val outstandingCents: Long,
    val reservedCents: Long,
    val openRecoveryCents: Long,
    val closed: Boolean,
)

data class Portfolio(
    val developerId: String,
    val limitCents: Long,
    val outstandingCents: Long,
    val reservedCents: Long,
    val payableCents: Long,
    val onHold: Boolean,
    val positions: List<PoolPosition>,
) {
    /** Credit headroom across the whole relationship, not per pool. */
    val headroomCents: Long
        get() = (limitCents - outstandingCents - reservedCents).coerceAtLeast(0)

    val openRecoveryCents: Long
        get() = positions.sumOf { it.openRecoveryCents }

    val fundedLifetimeCents: Long
        get() = positions.sumOf { it.fundedLifetimeCents }

    val openPools: Int
        get() = positions.count { !it.closed }

    /**
     * Share of outstanding principal sitting in the single largest pool, in basis points.
     *
     * A developer whose exposure is one pool is a different risk from one spread across many, even
     * at identical totals. Reported as an observation only; nothing in this lab acts on it yet.
     */
    val largestPoolShareBasisPoints: Int
        get() {
            val total = positions.sumOf { it.outstandingCents }
            if (total <= 0) return 0
            val largest = positions.maxOf { it.outstandingCents }
            return Math.toIntExact(largest * 10_000 / total)
        }
}

/**
 * Read-only aggregate of one developer's position across every pool.
 *
 * Single-pool views cannot show concentration, total headroom, or how much residual is waiting
 * behind an open recovery. This reads committed state only and takes no locks; a number here can be
 * stale the moment it is returned and must never be used to authorize funding. Reservation
 * re-checks capacity under lock for exactly that reason.
 */
class PortfolioView(private val database: Database) {

    fun of(developerId: String): Portfolio? = database.transaction { connection ->
        val developer =
            connection
                .rows(
                    "SELECT limit_cents,outstanding_cents,reserved_cents,payable_cents,hold FROM developers WHERE id=?",
                    developerId,
                ) {
                    listOf(
                        it.getLong(1),
                        it.getLong(2),
                        it.getLong(3),
                        it.getLong(4),
                        if (it.getBoolean(5)) 1L else 0L,
                    )
                }
                .singleOrNull() ?: return@transaction null
        val positions =
            connection.rows(
                """SELECT p.id,p.net_proceeds_cents,p.settled_proceeds_cents,p.funded_lifetime_cents,
                        p.outstanding_cents,p.reserved_cents,coalesce(r.open_cents,0),p.closed
                        FROM pools p LEFT JOIN recovery_obligations r ON r.pool_id=p.id
                        WHERE p.developer_id=? ORDER BY p.id""",
                developerId,
            ) {
                PoolPosition(
                    it.getString(1),
                    it.getLong(2),
                    it.getLong(3),
                    it.getLong(4),
                    it.getLong(5),
                    it.getLong(6),
                    it.getLong(7),
                    it.getBoolean(8),
                )
            }
        Portfolio(
            developerId,
            developer[0],
            developer[1],
            developer[2],
            developer[3],
            developer[4] == 1L,
            positions,
        )
    }
}
