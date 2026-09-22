package capital.payments

import capital.EvaluationContext
import capital.Snapshot
import capital.Status
import capital.config.RecoverySettings
import capital.decisionRecord
import capital.evaluate
import capital.policy.FinancialTerms
import java.math.BigInteger
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class FundingDeclined(message: String) : IllegalStateException(message)

class IdempotencyConflict :
    IllegalStateException("Idempotency key was already bound to another request")

data class AdvanceRequest(
    val developerId: String,
    val poolId: String,
    val principalCents: Long,
    val destinationVersion: String,
    val currency: String = "USD",
)

data class Advance(
    val id: UUID,
    val developerId: String,
    val poolId: String,
    val requestHash: String,
    val principalCents: Long,
    val feeCents: Long,
    val cashCents: Long,
    val currency: String,
    val destinationVersion: String,
    val providerKey: String,
    val state: String,
    val generation: Long,
    val leaseUntil: Instant?,
    val bankTransferId: String?,
    val lastReason: String?,
) {
    fun command() = BankCommand(providerKey, cashCents, currency, destinationVersion)
}

private data class Developer(
    val limit: Long,
    val outstanding: Long,
    val reserved: Long,
    val hold: Boolean,
    val destination: String,
)

private data class Pool(
    val id: String,
    val total: Long,
    val settled: Long,
    val fundedLifetime: Long,
    val outstanding: Long,
    val reserved: Long,
    val reportThrough: LocalDate,
    val downloaded: Instant,
    val closed: Boolean,
)

private data class Controls(
    val cash: Long,
    val cashReserved: Long,
    val developer: Developer,
    val pool: Pool,
)

private data class Check(val principalCapacity: Long, val reason: String?, val evidence: String)

/** A non-binding view of current capacity, with the reason when none is offered. */
data class Availability(
    val principalCents: Long,
    val blockedReason: String?,
    val feeCents: Long,
    val evidenceJson: String,
) {
    val netCashCents: Long
        get() = principalCents - feeCents
}

private data class Claim(val advance: Advance, val recovery: Boolean)

/**
 * Single provider/currency policy slice. Authenticated caller identity and source calendar are
 * upstream responsibilities.
 */
class AdvanceService(
    private val database: Database,
    private val clock: Clock,
    private val expectedReportThrough: LocalDate,
    private val leaseDuration: Duration = RecoverySettings().lease,
    private val retryDelay: Duration = RecoverySettings().retryDelay,
    private val terms: FinancialTerms = FinancialTerms.EARLY_PAYOUTS_V1,
) {
    fun reserve(request: AdvanceRequest, key: String): Advance {
        require(key.isNotBlank() && key.length <= 200)
        require(request.principalCents > 0 && request.currency == "USD")
        require(
            request.developerId.isNotBlank() &&
                request.poolId.isNotBlank() &&
                request.destinationVersion.isNotBlank()
        )
        val hash =
            fingerprint(
                request.developerId,
                request.poolId,
                request.principalCents,
                request.destinationVersion,
                request.currency,
            )
        return database.transaction { connection ->
            lockTreasury(connection)
            lockDeveloper(connection, request.developerId)
            val existing =
                connection
                    .rows(
                        "SELECT * FROM advances WHERE developer_id = ? AND request_key = ?",
                        request.developerId,
                        key,
                        read = ::advanceRow,
                    )
                    .singleOrNull()
            if (existing != null) {
                if (existing.requestHash != hash) throw IdempotencyConflict()
                return@transaction existing
            }
            val controls = controls(connection, request.developerId, request.poolId)
            val check = capacity(controls, request.destinationVersion)
            if (check.reason != null || request.principalCents > check.principalCapacity)
                throw FundingDeclined(check.reason ?: "INSUFFICIENT_CREDIT_CAPACITY")
            val fee = terms.fee(request.principalCents)
            val cash = request.principalCents - fee
            if (cash > controls.cash - controls.cashReserved)
                throw FundingDeclined("INSUFFICIENT_FUNDING_CASH")
            val id = UUID.randomUUID()
            connection.update(
                """INSERT INTO advances(id,developer_id,pool_id,request_key,request_hash,principal_cents,fee_cents,cash_cents,
                currency,destination_version,provider_key,decision_json,state,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'READY',?)""",
                id,
                request.developerId,
                request.poolId,
                key,
                hash,
                request.principalCents,
                fee,
                cash,
                request.currency,
                request.destinationVersion,
                "payment_$id",
                check.evidence,
                clock.instant(),
            )
            connection.update(
                "UPDATE treasury SET reserved_cash_cents = reserved_cash_cents + ? WHERE id = 1",
                cash,
            )
            connection.update(
                "UPDATE developers SET reserved_cents = reserved_cents + ? WHERE id = ?",
                request.principalCents,
                request.developerId,
            )
            connection.update(
                "UPDATE pools SET reserved_cents = reserved_cents + ? WHERE id = ?",
                request.principalCents,
                request.poolId,
            )
            connection.update(
                "INSERT INTO outbox(advance_id,next_attempt_at) VALUES (?,?)",
                id,
                clock.instant(),
            )
            readAdvance(connection, id)
        }
    }

    fun get(id: UUID): Advance = database.transaction { readAdvance(it, id) }

    /**
     * How much new principal this pool could support right now, with the policy evidence behind
     * that number. A blocking reason yields zero rather than a figure a caller might act on.
     *
     * This is a quote, not a reservation: capacity is re-checked inside [reserve] under the same
     * locks, so two callers reading the same availability cannot both consume it.
     */
    fun available(developerId: String, poolId: String): Availability =
        database.transaction { connection ->
            val controls = controls(connection, developerId, poolId)
            val check = capacity(controls, controls.developer.destination)
            Availability(
                if (check.reason != null) 0 else check.principalCapacity,
                check.reason,
                terms.fee(if (check.reason != null) 0 else check.principalCapacity),
                check.evidence,
            )
        }

    fun setHold(developerId: String, active: Boolean) = database.transaction { connection ->
        lockDeveloper(connection, developerId)
        connection.update("UPDATE developers SET hold = ? WHERE id = ?", active, developerId)
    }

    /**
     * Poll one durable intent. Concurrent pollers may find the same row; only a claimed lease
     * proceeds.
     */
    fun processNext(bank: BankGateway): Boolean {
        val id =
            database.transaction { connection ->
                connection
                    .rows(
                        """SELECT o.advance_id FROM outbox o JOIN advances a ON a.id=o.advance_id
                WHERE NOT o.done AND o.next_attempt_at <= ? AND (a.lease_until IS NULL OR a.lease_until <= ?)
                ORDER BY a.created_at,a.id LIMIT 1""",
                        clock.instant(),
                        clock.instant(),
                    ) {
                        it.getObject(1, UUID::class.java)
                    }
                    .singleOrNull()
            } ?: return false
        return process(id, bank)
    }

    fun process(id: UUID, bank: BankGateway): Boolean {
        val claimed = claim(id) ?: return false
        val command = claimed.advance.command()
        // There is deliberately NO capital database transaction open across these HTTP calls.
        val observation =
            try {
                if (!claimed.recovery) bank.submit(command)
                else
                    when (val lookup = bank.lookup(command)) {
                        BankResult.NotFound -> {
                            // A new hold may have arrived while lookup was in flight. Recheck
                            // before any POST.
                            if (mayResubmit(claimed.advance)) bank.submit(command)
                            else BankResult.Unknown("QUERY_ONLY_HOLD_OR_CHANGED_ELIGIBILITY")
                        }
                        else -> lookup
                    }
            } catch (_: Exception) {
                BankResult.Unknown("GATEWAY_EXCEPTION")
            }
        finish(claimed.advance, observation)
        return true
    }

    private fun claim(id: UUID): Claim? = database.transaction { connection ->
        val unlocked = readAdvance(connection, id)
        val controls = controls(connection, unlocked.developerId, unlocked.poolId)
        val current = readAdvance(connection, id, lock = true)
        if (current.state in terminal || (current.leaseUntil?.isAfter(clock.instant()) == true))
            return@transaction null
        val retryAt =
            connection
                .rows("SELECT next_attempt_at FROM outbox WHERE advance_id = ?", id) {
                    it.getTimestamp(1).toInstant()
                }
                .single()
        if (retryAt.isAfter(clock.instant())) return@transaction null
        if (current.state == "READY") {
            val check = capacity(controls, current.destinationVersion, current.principalCents)
            if (check.reason != null || current.principalCents > check.principalCapacity) {
                release(
                    connection,
                    current,
                    "CANCELED",
                    check.reason ?: "CAPACITY_CHANGED_BEFORE_DISPATCH",
                )
                return@transaction null
            }
        }
        val recovery = current.state != "READY"
        connection.update(
            "UPDATE advances SET state='DISPATCHING',generation=generation+1,lease_until=? WHERE id=?",
            clock.instant().plus(leaseDuration),
            id,
        )
        connection.update("UPDATE outbox SET attempts=attempts+1 WHERE advance_id=?", id)
        Claim(readAdvance(connection, id), recovery)
    }

    private fun mayResubmit(claimed: Advance): Boolean = database.transaction { connection ->
        val controls = controls(connection, claimed.developerId, claimed.poolId)
        val current = readAdvance(connection, claimed.id, lock = true)
        if (current.generation != claimed.generation || current.state != "DISPATCHING")
            return@transaction false
        val check = capacity(controls, current.destinationVersion, current.principalCents)
        check.reason == null && current.principalCents <= check.principalCapacity
    }

    private fun finish(claimed: Advance, observation: BankResult) =
        database.transaction { connection ->
            controls(connection, claimed.developerId, claimed.poolId)
            val current = readAdvance(connection, claimed.id, lock = true)
            if (current.state in terminal || current.generation != claimed.generation)
                return@transaction
            when (observation) {
                is BankResult.Settled -> {
                    // Simulator SETTLED is both confirmed cash debit and funding; real providers
                    // need separate facts.
                    connection.update(
                        "UPDATE treasury SET reserved_cash_cents=reserved_cash_cents-?,cash_cents=cash_cents-? WHERE id=1",
                        current.cashCents,
                        current.cashCents,
                    )
                    connection.update(
                        "UPDATE developers SET reserved_cents=reserved_cents-?,outstanding_cents=outstanding_cents+? WHERE id=?",
                        current.principalCents,
                        current.principalCents,
                        current.developerId,
                    )
                    connection.update(
                        """UPDATE pools SET reserved_cents=reserved_cents-?,outstanding_cents=outstanding_cents+?,
                    funded_lifetime_cents=funded_lifetime_cents+? WHERE id=?""",
                        current.principalCents,
                        current.principalCents,
                        current.principalCents,
                        current.poolId,
                    )
                    val journal = UUID.randomUUID()
                    connection.update(
                        "INSERT INTO journals(id,advance_id,posting_key,created_at) VALUES (?,?,?,?)",
                        journal,
                        current.id,
                        "funding_${current.id}",
                        clock.instant(),
                    )
                    entry(
                        connection,
                        journal,
                        "ADVANCE_RECEIVABLE",
                        "DEBIT",
                        current.principalCents,
                    )
                    entry(connection, journal, "FUNDING_CASH", "CREDIT", current.cashCents)
                    if (current.feeCents > 0)
                        entry(connection, journal, "DEFERRED_FEE", "CREDIT", current.feeCents)
                    connection.update(
                        "UPDATE advances SET state='SETTLED',bank_transfer_id=?,lease_until=NULL,last_reason=NULL WHERE id=?",
                        observation.transferId,
                        current.id,
                    )
                    connection.update("UPDATE outbox SET done=TRUE WHERE advance_id=?", current.id)
                }
                BankResult.Rejected ->
                    release(connection, current, "REJECTED", "BANK_CONFIRMED_REJECTION")
                else -> {
                    val reason =
                        (observation as? BankResult.Unknown)?.reason ?: "BANK_OUTCOME_NOT_FOUND"
                    connection.update(
                        "UPDATE advances SET state='UNKNOWN',lease_until=NULL,last_reason=? WHERE id=?",
                        reason,
                        current.id,
                    )
                    connection.update(
                        "UPDATE outbox SET next_attempt_at=? WHERE advance_id=?",
                        clock.instant().plus(retryDelay),
                        current.id,
                    )
                }
            }
        }

    private fun release(connection: Connection, advance: Advance, state: String, reason: String) {
        connection.update(
            "UPDATE treasury SET reserved_cash_cents=reserved_cash_cents-? WHERE id=1",
            advance.cashCents,
        )
        connection.update(
            "UPDATE developers SET reserved_cents=reserved_cents-? WHERE id=?",
            advance.principalCents,
            advance.developerId,
        )
        connection.update(
            "UPDATE pools SET reserved_cents=reserved_cents-? WHERE id=?",
            advance.principalCents,
            advance.poolId,
        )
        connection.update(
            "UPDATE advances SET state=?,lease_until=NULL,last_reason=? WHERE id=?",
            state,
            reason,
            advance.id,
        )
        connection.update("UPDATE outbox SET done=TRUE WHERE advance_id=?", advance.id)
    }

    private fun capacity(controls: Controls, destination: String, ownReservation: Long = 0): Check {
        val developer = controls.developer
        val pool = controls.pool
        val poolReserved = Math.subtractExact(pool.reserved, ownReservation)
        val developerReserved = Math.subtractExact(developer.reserved, ownReservation)
        require(poolReserved >= 0 && developerReserved >= 0)
        val remainingProceeds =
            (BigInteger.valueOf(pool.total) - BigInteger.valueOf(pool.settled))
                .max(BigInteger.ZERO)
                .longValueExact()
        val decision =
            evaluate(
                Snapshot(
                    pool.id,
                    "snapshot_${fingerprint(pool.id, pool.total, pool.settled, pool.reportThrough.toString(), pool.downloaded.toString())}",
                    pool.reportThrough,
                    pool.downloaded,
                    remainingProceeds,
                    Math.addExact(pool.outstanding, poolReserved),
                ),
                terms.quotePolicy(developer.limit),
                EvaluationContext(clock.instant(), expectedReportThrough, ZoneId.of("UTC")),
            )
        val origination =
            (BigInteger.valueOf(terms.advanceLimit(pool.total)) -
                    BigInteger.valueOf(pool.fundedLifetime) -
                    BigInteger.valueOf(poolReserved))
                .max(BigInteger.ZERO)
                .longValueExact()
        val borrower =
            (BigInteger.valueOf(developer.limit) -
                    BigInteger.valueOf(developer.outstanding) -
                    BigInteger.valueOf(developerReserved))
                .max(BigInteger.ZERO)
                .longValueExact()
        val reason =
            when {
                developer.hold -> "RISK_HOLD"
                developer.destination != destination -> "DESTINATION_CHANGED"
                pool.closed -> "POOL_CLOSED"
                decision.status == Status.HOLD -> "REPORT_COVERAGE_BEHIND"
                else -> null
            }
        val evidence =
            decisionRecord("transactional-reservation", decision)
                .apply {
                    addProperty("cumulativeNetProceedsCents", pool.total)
                    addProperty("lifetimeFundedCents", pool.fundedLifetime)
                    addProperty("settledProceedsCents", pool.settled)
                    addProperty("originationHeadroomCents", origination)
                    addProperty("borrowerHeadroomCents", borrower)
                    addProperty("riskHold", developer.hold)
                }
                .toString()
        return Check(
            minOf(decision.eligiblePrincipalCents, origination, borrower),
            reason,
            evidence,
        )
    }

    private fun controls(connection: Connection, developerId: String, poolId: String): Controls {
        val cash = lockTreasury(connection)
        val developer = lockDeveloper(connection, developerId)
        val pool =
            connection
                .rows(
                    "SELECT * FROM pools WHERE id=? AND developer_id=? FOR UPDATE",
                    poolId,
                    developerId,
                ) {
                    Pool(
                        it.getString("id"),
                        it.getLong("net_proceeds_cents"),
                        it.getLong("settled_proceeds_cents"),
                        it.getLong("funded_lifetime_cents"),
                        it.getLong("outstanding_cents"),
                        it.getLong("reserved_cents"),
                        it.getDate("report_through").toLocalDate(),
                        it.getTimestamp("downloaded_at").toInstant(),
                        it.getBoolean("closed"),
                    )
                }
                .singleOrNull() ?: throw FundingDeclined("POOL_NOT_FOUND_FOR_DEVELOPER")
        return Controls(cash.first, cash.second, developer, pool)
    }

    private fun lockTreasury(connection: Connection): Pair<Long, Long> =
        connection
            .rows("SELECT cash_cents,reserved_cash_cents FROM treasury WHERE id=1 FOR UPDATE") {
                it.getLong(1) to it.getLong(2)
            }
            .single()

    private fun lockDeveloper(connection: Connection, id: String): Developer =
        connection
            .rows("SELECT * FROM developers WHERE id=? FOR UPDATE", id) {
                Developer(
                    it.getLong("limit_cents"),
                    it.getLong("outstanding_cents"),
                    it.getLong("reserved_cents"),
                    it.getBoolean("hold"),
                    it.getString("destination_version"),
                )
            }
            .singleOrNull() ?: throw FundingDeclined("DEVELOPER_NOT_FOUND")

    private fun readAdvance(connection: Connection, id: UUID, lock: Boolean = false): Advance =
        connection
            .rows(
                "SELECT * FROM advances WHERE id=?" + if (lock) " FOR UPDATE" else "",
                id,
                read = ::advanceRow,
            )
            .single()

    private fun entry(
        connection: Connection,
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

    private val terminal = setOf("SETTLED", "REJECTED", "CANCELED")
}

private fun advanceRow(row: ResultSet): Advance =
    Advance(
        row.getObject("id", UUID::class.java),
        row.getString("developer_id"),
        row.getString("pool_id"),
        row.getString("request_hash"),
        row.getLong("principal_cents"),
        row.getLong("fee_cents"),
        row.getLong("cash_cents"),
        row.getString("currency"),
        row.getString("destination_version"),
        row.getString("provider_key"),
        row.getString("state"),
        row.getLong("generation"),
        row.getTimestamp("lease_until")?.toInstant(),
        row.getString("bank_transfer_id"),
        row.getString("last_reason"),
    )
