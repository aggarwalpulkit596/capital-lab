package capital.dashboard

import capital.*
import capital.collections.*
import capital.payments.*
import capital.policy.FinancialTerms
import capital.reconciliation.*
import capital.risk.*
import capital.simulation.ScenarioClock
import capital.simulation.fixtureFor
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.sql.Connection
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class SimulatedWorkerStop : Error("Simulated interruption after bank execution")

private data class StepEvent(
    val number: Int,
    val title: String,
    val time: String,
    val message: String,
    val state: JsonObject,
)

private class RunProgress {
    var nextStep = 0
    var message = "Scenario created with isolated database schemas. Choose Next step to begin."
    val events = mutableListOf<StepEvent>()
}

/** Observations for the walkthrough; durable financial state remains in PostgreSQL. */
private class RunObservations {
    var advance: Advance? = null
    var assessment: JsonObject? = null
    var signals = emptyList<Signal>()
    var collection: CollectionResult? = null
    var statementMismatch = false
    var postRequests = 0
    var lookupRequests = 0
}

/**
 * Orchestrates teaching scenarios. Financial serialization remains in PostgreSQL, not this UI lock.
 */
class LabRun(
    val id: String,
    val scenario: Scenario,
    val capital: Database,
    val bankDatabase: Database,
    val datasetDate: String?,
    val purpose: String = "scenario",
    private val terms: FinancialTerms = FinancialTerms.EARLY_PAYOUTS_V1,
    private val monitoring: MonitoringPolicy = MonitoringPolicy.DEMO_V1,
) {
    private val fixture = fixtureFor(scenario.id, datasetDate, terms, monitoring)
    private val clock = ScenarioClock(fixture.evaluatedAt)
    private val period = fixture.expectedPeriod

    private fun newService() = AdvanceService(capital, clock, period, terms = terms)

    private var service = newService()
    private val progress = RunProgress()
    private val observations = RunObservations()

    init {
        fixture.seed(capital, bankDatabase)
        capital.install("/db/dashboard.sql")
        bankDatabase.install("/db/bank-dashboard.sql")
    }

    @Synchronized
    fun step(expectedStep: Int): JsonObject {
        require(expectedStep >= 0)
        // Browser retries/double clicks cannot advance twice. A stale step returns the
        // current
        // state.
        if (expectedStep < progress.nextStep) return snapshot()
        require(expectedStep == progress.nextStep && progress.nextStep < scenario.steps.size) {
            "Step is not available"
        }
        val title = scenario.steps[progress.nextStep]
        progress.message = perform(progress.nextStep)
        progress.nextStep++
        val state = state()
        progress.events.add(
            StepEvent(progress.nextStep, title, clock.instant().toString(), progress.message, state)
        )
        return snapshot()
    }

    @Synchronized
    fun snapshot(): JsonObject =
        JsonObject().apply {
            addProperty("id", id)
            addProperty("purpose", purpose)
            addProperty("archived", false)
            add("scenario", Gson().toJsonTree(scenario))
            addProperty("nextStep", progress.nextStep)
            addProperty("complete", progress.nextStep == scenario.steps.size)
            addProperty("message", progress.message)
            addProperty("datasetDate", datasetDate)
            addProperty("capitalSchema", capital.schema)
            addProperty("bankSchema", bankDatabase.schema)
            add("assessment", observations.assessment)
            add("signals", Gson().toJsonTree(observations.signals))
            add("riskInputs", Gson().toJsonTree(fixture.riskInputs))
            add("monitoringPolicy", Gson().toJsonTree(monitoring))
            add("collection", Gson().toJsonTree(observations.collection))
            add("state", state())
            add("events", Gson().toJsonTree(progress.events))
            addProperty(
                "scope",
                "Local lab · synthetic money · illustrative risk rules · simulated bank",
            )
        }

    private fun perform(step: Int): String {
        if (step == 0) return assess()
        when (scenario.id) {
            "stale",
            "refund",
            "fraud",
            "cancellations",
            "liquidity" ->
                return if (step == 1) reserve()
                else
                    "No payment was authorized. Reservations, bank cash, and ledger remain unchanged."
            "race",
            "duplicate" -> {
                if (step == 1) return race(scenario.id == "duplicate")
                if (scenario.id == "duplicate" && step == 2) {
                    return try {
                        service.reserve(request().copy(principalCents = 10_000), "duplicate")
                        error("Expected conflict")
                    } catch (_: IdempotencyConflict) {
                        "Changed principal with the same key was rejected. The original command is unchanged."
                    }
                }
                if (step == if (scenario.id == "duplicate") 3 else 2) return dispatch()
                return replay()
            }
        }
        if (step == 1) return reserve()
        return when (scenario.id) {
            "happy",
            "public-data" -> if (step == 2) dispatch() else replay()
            "lost-held" ->
                when (step) {
                    2 -> dispatch(BankFault.LOSE_RESPONSE_AFTER_COMMIT)
                    3 -> hold(true)
                    4 -> recover()
                    else -> replay()
                }
            "not-found-held" ->
                when (step) {
                    2 -> dispatch(BankFault.LOSE_RESPONSE_BEFORE_EXECUTION)
                    3 -> hold(true)
                    4 -> recover()
                    5 -> {
                        hold(false)
                        recover()
                    }
                    else -> replay()
                }
            "retry" ->
                when (step) {
                    2 -> dispatch(BankFault.LOSE_RESPONSE_BEFORE_EXECUTION)
                    3 -> recover()
                    else -> replay()
                }
            "crash" ->
                when (step) {
                    2 -> {
                        withBank { gateway ->
                            try {
                                service.process(
                                    requireNotNull(observations.advance).id,
                                    object : BankGateway {
                                        override fun submit(command: BankCommand): BankResult {
                                            gateway.submit(command)
                                            throw SimulatedWorkerStop()
                                        }

                                        override fun lookup(command: BankCommand): BankResult =
                                            gateway.lookup(command)
                                    },
                                )
                            } catch (_: SimulatedWorkerStop) {
                                /* Leave the durable claim unfinished, just as a stopped worker does. */
                            }
                        }
                        "Bank executed; local posting did not run. The operation remains DISPATCHING with its reservation."
                    }
                    3 -> {
                        clock.advance(Duration.ofSeconds(31))
                        recover()
                    }
                    else -> replay()
                }
            "rejected" -> if (step == 2) dispatch(BankFault.REJECT_BEFORE_PAYMENT) else replay()
            "hold-before" ->
                when (step) {
                    2 -> hold(true)
                    3 -> dispatch()
                    else -> replay()
                }
            "destination" ->
                when (step) {
                    2 -> {
                        capital.transaction {
                            it.update(
                                "UPDATE developers SET destination_version='verified-destination-v2'"
                            )
                        }
                        "Verified destination changed. The reserved command still targets v1."
                    }
                    3 -> dispatch()
                    else -> replay()
                }
            "mismatch" ->
                if (step == 2) dispatch()
                else {
                    observations.statementMismatch = true
                    "Injected a +$1 discrepancy into an independent statement copy. The underlying bank record and ledger are unchanged."
                }
            "collection",
            "shortfall",
            "collection-mismatch" -> if (step == 2) dispatch() else collect()
            else -> error("Unknown scenario")
        }
    }

    private fun assess(): String {
        observations.signals = monitor(fixture.riskInputs, monitoring)
        if (observations.signals.any { it.severity == "HOLD" })
            service.setHold(fixture.developerId, true)
        observations.assessment = capital.transaction { c ->
            val snapshot =
                c.rows("SELECT * FROM pools WHERE id=?", fixture.poolId) {
                        Snapshot(
                            fixture.poolId,
                            "lab-source-v1",
                            it.getDate("report_through").toLocalDate(),
                            it.getTimestamp("downloaded_at").toInstant(),
                            it.getLong("net_proceeds_cents"),
                            it.getLong("outstanding_cents"),
                        )
                    }
                    .single()
            decisionRecord(
                "dashboard",
                evaluate(
                    snapshot,
                    terms.quotePolicy(c.number("SELECT limit_cents FROM developers")),
                    EvaluationContext(clock.instant(), period, ZoneOffset.UTC),
                ),
            )
        }
        return if (observations.signals.any { it.severity == "HOLD" })
            "Monitoring placed a risk hold. The arithmetic quote is diagnostic; authorization will enforce the hold."
        else
            "Calculated quote and freshness reasons. Authorization will recheck capacity, holds, destination, and funding cash inside a transaction."
    }

    private fun request() = fixture.request()

    private fun reserve(): String =
        try {
            observations.advance = service.reserve(request(), "dashboard-advance")
            "Principal and net cash reserved atomically with the dispatch intent. No bank payment yet."
        } catch (failure: FundingDeclined) {
            "Reservation declined: ${failure.message}. No payment was created."
        }

    private fun hold(active: Boolean): String {
        service.setHold(fixture.developerId, active)
        return if (active) "New risk hold applied. Unknown bank outcomes retain their reservations."
        else "Hold cleared. Recovery must still resolve the original bank identity."
    }

    private fun withBank(fault: BankFault = BankFault.NONE, operation: (HttpBankGateway) -> Unit) {
        FakeBankServer(bankDatabase).use { server ->
            server.nextFault.set(fault)
            try {
                operation(HttpBankGateway(server.endpoint))
            } finally {
                observations.postRequests += server.postRequests.get()
                observations.lookupRequests += server.lookupRequests.get()
            }
        }
    }

    private fun dispatch(fault: BankFault = BankFault.NONE): String {
        val current =
            observations.advance ?: return "No authorized advance exists. No bank request was sent."
        withBank(fault) { service.process(current.id, it) }
        val result = service.get(current.id)
        return "Operation is ${result.state}. ${result.lastReason ?: "Bank observation and local posting recorded."}"
    }

    private fun recover(): String {
        clock.advance(Duration.ofSeconds(3))
        service = newService()
        return dispatch()
    }

    private fun replay(): String {
        observations.advance?.let { withBank { gateway -> service.process(it.id, gateway) } }
        return "Compared payment identities, amounts, bank evidence, and cash postings. Replaying a terminal operation has no additional effect."
    }

    private fun race(identical: Boolean): String {
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures =
                (0 until 8).map { index ->
                    executor.submit<Advance?> {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        try {
                            newService()
                                .reserve(
                                    request(),
                                    if (identical) "duplicate" else "competitor-$index",
                                )
                        } catch (_: FundingDeclined) {
                            null
                        }
                    }
                }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.mapNotNull { it.get(15, TimeUnit.SECONDS) }
            observations.advance = results.first()
            check(results.map { it.id }.toSet().size == 1)
            return "${results.size} of 8 calls returned an operation; ${results.map { it.id }.toSet().size} unique reservation. PostgreSQL enforced the shared limit."
        } finally {
            executor.shutdownNow()
        }
    }

    private fun collect(): String {
        val receiptAmount =
            when (scenario.id) {
                "shortfall" -> 70_000L
                "collection-mismatch" -> 99_500L
                else -> 100_000L
            }
        val reportAmount = if (scenario.id == "shortfall") 70_000L else 100_000L
        bankDatabase.transaction {
            it.update(
                "INSERT INTO lab_store_receipts(id,report_id,pool_id,cents,currency) VALUES ('receipt-1','report-1','pool-1',?,'USD') ON CONFLICT(id) DO NOTHING",
                receiptAmount,
            )
        }
        val receipt = bankDatabase.transaction { c ->
            c.rows("SELECT * FROM lab_store_receipts WHERE id='receipt-1'") {
                    StoreReceipt(
                        it.getString("id"),
                        it.getString("report_id"),
                        it.getString("pool_id"),
                        it.getLong("cents"),
                        it.getString("currency"),
                    )
                }
                .single()
        }
        observations.collection =
            CollectionService(capital)
                .allocate(receipt, FinalReport("report-1", "pool-1", reportAmount))
        return "${observations.collection!!.status}: ${observations.collection!!.explanation}"
    }

    private fun state(): JsonObject {
        val local = capital.transaction { c ->
            JsonObject().apply {
                add("treasury", Gson().toJsonTree(c.table("SELECT * FROM treasury")))
                add(
                    "developers",
                    Gson().toJsonTree(c.table("SELECT * FROM developers ORDER BY id")),
                )
                add("pools", Gson().toJsonTree(c.table("SELECT * FROM pools ORDER BY id")))
                add(
                    "advances",
                    Gson().toJsonTree(c.table("SELECT * FROM advances ORDER BY created_at,id")),
                )
                add(
                    "outbox",
                    Gson().toJsonTree(c.table("SELECT * FROM outbox ORDER BY advance_id")),
                )
                add(
                    "ledger",
                    Gson()
                        .toJsonTree(
                            c.table(
                                "SELECT j.posting_key,e.account,e.side,e.cents FROM journals j JOIN ledger_entries e ON e.journal_id=j.id ORDER BY e.id"
                            )
                        ),
                )
                add(
                    "collections",
                    Gson().toJsonTree(c.table("SELECT * FROM lab_collections ORDER BY receipt_id")),
                )
                add(
                    "collectionLedger",
                    Gson()
                        .toJsonTree(
                            c.table(
                                "SELECT receipt_id,account,side,cents FROM lab_collection_entries ORDER BY id"
                            )
                        ),
                )
            }
        }
        bankDatabase.transaction { c ->
            local.add(
                "bank",
                Gson()
                    .toJsonTree(
                        c.table(
                            "SELECT provider_key,transfer_id,amount_cents,currency,destination,state,submit_count FROM bank_operations ORDER BY created_at"
                        )
                    ),
            )
            local.addProperty(
                "bankCashCents",
                c.number("SELECT cents FROM bank_balance WHERE id=1"),
            )
            local.add(
                "storeReceipts",
                Gson().toJsonTree(c.table("SELECT * FROM lab_store_receipts ORDER BY id")),
            )
        }
        val payments =
            local.getAsJsonArray("advances").map { element ->
                val a = element.asJsonObject
                val posted = capital.transaction {
                    it.number(
                        "SELECT coalesce(sum(e.cents),0) FROM ledger_entries e JOIN journals j ON j.id=e.journal_id WHERE j.advance_id=? AND e.account='FUNDING_CASH' AND e.side='CREDIT'",
                        UUID.fromString(a["id"].asString),
                    )
                }
                PaymentEvidence(
                    a["provider_key"].asString,
                    a["bank_transfer_id"]?.takeUnless { it.isJsonNull }?.asString,
                    a["cash_cents"].asLong,
                    a["currency"].asString,
                    a["state"].asString,
                    posted,
                )
            }
        val statement =
            local
                .getAsJsonArray("bank")
                .filter { it.asJsonObject["state"].asString == "SETTLED" }
                .map {
                    val b = it.asJsonObject
                    StatementLine(
                        b["provider_key"].asString,
                        b["transfer_id"].asString,
                        b["amount_cents"].asLong + if (observations.statementMismatch) 100 else 0,
                        b["currency"].asString,
                    )
                }
        local.add("reconciliation", Gson().toJsonTree(reconcile(payments, statement)))
        local.addProperty("statementFaultInjected", observations.statementMismatch)
        local.addProperty("bankPostRequests", observations.postRequests)
        local.addProperty("bankLookupRequests", observations.lookupRequests)
        local.addProperty("clock", clock.instant().toString())
        return local
    }
}

internal fun Connection.table(sql: String): List<Map<String, Any?>> =
    rows(sql) { row ->
        (1..row.metaData.columnCount).associate { index ->
            val value = row.getObject(index)
            row.metaData.getColumnLabel(index) to
                when (value) {
                    null -> null
                    is Number,
                    is Boolean -> value
                    else -> value.toString()
                }
        }
    }
