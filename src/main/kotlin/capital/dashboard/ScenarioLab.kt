package capital.dashboard

import capital.*
import capital.payments.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.sql.Connection
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Scenario(val id: String, val title: String, val category: String, val description: String, val steps: List<String>)
private fun scenario(id: String, title: String, category: String, description: String, vararg steps: String) = Scenario(id, title, category, description, steps.toList())
val scenarios = listOf(
    scenario("happy", "A normal advance", "Money movement", "Reserve capacity, fund once, and match the bank debit to the ledger.", "Assess eligibility", "Reserve $200 principal", "Dispatch to the bank", "Reconcile payment"),
    scenario("lost-held", "Paid, but the response vanished", "Recovery", "The bank paid. A new hold allows queries, but no resubmission.", "Assess eligibility", "Reserve $200 principal", "Lose response after bank payment", "Apply a new risk hold", "Recover by query", "Replay and reconcile"),
    scenario("not-found-held", "Unknown outcome + hold", "Recovery", "No bank record is found. Reservations remain until the hold clears and recovery can proceed.", "Assess eligibility", "Reserve $200 principal", "Lose response before execution", "Apply a new risk hold", "Query only; keep reservation", "Clear hold and recover same key", "Reconcile payment"),
    scenario("retry", "Retry with the same identity", "Recovery", "An unknown operation is absent at the bank; an eligible recovery retries its frozen key.", "Assess eligibility", "Reserve $200 principal", "Lose response before execution", "Recover with same bank key", "Reconcile payment"),
    scenario("crash", "Worker stops after bank payment", "Recovery", "Simulate interruption after the HTTP call, before the local posting. The integration suite also tests an actual JVM halt.", "Assess eligibility", "Reserve $200 principal", "Interrupt after bank execution", "Expire lease and recover", "Reconcile payment"),
    scenario("rejected", "Bank rejects the payment", "Bank integration", "A definite rejection releases the reservation and creates no funding journal.", "Assess eligibility", "Reserve $200 principal", "Receive bank rejection", "Reconcile payment"),
    scenario("hold-before", "Hold before dispatch", "Risk monitoring", "A provably undispatched payment is canceled and its reservation released.", "Assess eligibility", "Reserve $200 principal", "Apply hold before dispatch", "Dispatch recheck", "Reconcile payment"),
    scenario("destination", "Destination changes", "Bank integration", "The frozen destination cannot be silently replaced.", "Assess eligibility", "Reserve $200 principal", "Change verified destination", "Dispatch recheck", "Reconcile payment"),
    scenario("stale", "Fresh download, old coverage", "Underwriting", "The report download is current, but its reporting period is behind.", "Assess stale report", "Attempt reservation", "Inspect unchanged balances"),
    scenario("refund", "Refunds erase capacity", "Underwriting", "Net proceeds fall to $700 with $600 outstanding: $40 exposure exceeds the new limit.", "Assess revised proceeds", "Attempt reservation", "Inspect unchanged balances"),
    scenario("fraud", "Revenue spike → review", "Fraud review", "An illustrative 3× velocity rule creates a hold. A revenue spike is not proof of fraud.", "Evaluate velocity signal", "Attempt reservation", "Inspect unchanged balances"),
    scenario("cancellations", "Cancellations increase", "Risk monitoring", "Cancellations above the illustrative 10% threshold pause advances for review.", "Evaluate cancellation signal", "Attempt reservation", "Inspect unchanged balances"),
    scenario("liquidity", "Credit available, cash unavailable", "Money movement", "Eligible credit does not imply sufficient funding-account cash.", "Assess eligibility", "Attempt reservation", "Inspect unchanged balances"),
    scenario("race", "Eight requests, one capacity pool", "Concurrency", "Eight concurrent database clients compete for the last $200 of principal capacity.", "Assess eligibility", "Race eight distinct requests", "Dispatch the winning reservation", "Reconcile payment"),
    scenario("duplicate", "Eight copies of one request", "Concurrency", "Concurrent duplicate keys return one durable operation. A changed amount conflicts.", "Assess eligibility", "Race eight identical requests", "Try a conflicting payload", "Dispatch once", "Replay and reconcile"),
    scenario("mismatch", "Bank statement discrepancy", "Reconciliation", "A deliberately altered statement copy differs by $1. The exception stays open; no journal is rewritten.", "Assess eligibility", "Reserve $200 principal", "Dispatch to the bank", "Import a mismatching statement"),
    scenario("collection", "Store collection and repayment", "Reconciliation", "Match a final $1,000 receipt, repay $800 principal, and leave $200 payable to the developer.", "Assess eligibility", "Reserve $200 principal", "Dispatch to the bank", "Match final store collection", "Replay collection; no second repayment"),
    scenario("shortfall", "Final collection is short", "Reconciliation", "A final $700 report and receipt repay $700 of $800 outstanding. Keep the $100 shortfall and close the pool.", "Assess eligibility", "Reserve $200 principal", "Dispatch to the bank", "Allocate the final $700 receipt", "Replay collection; retain shortfall"),
    scenario("collection-mismatch", "Store report ≠ bank receipt", "Reconciliation", "A $1,000 report and $995 credit remain unapplied. Never invent a $5 fee to force a match.", "Assess eligibility", "Reserve $200 principal", "Dispatch to the bank", "Compare report and collection", "Keep exception open"),
    scenario("public-data", "Replay a real retail day", "Public dataset", "Replay UCI sales/cancellations through illustrative rules, then simulated USD payments. No app-store or fraud labels.", "Normalize and assess selected day", "Attempt advance reservation", "Dispatch if eligible", "Reconcile payment")
)

private class SimulatedWorkerStop : Error("Simulated interruption after bank execution")
private data class StepEvent(val number: Int, val title: String, val time: String, val message: String, val state: JsonObject)

/** Orchestrates teaching scenarios. Financial serialization remains in PostgreSQL, not this UI lock. */
class LabRun(val id: String, val scenario: Scenario, val capital: Database, val bankDatabase: Database, val datasetDate: String?) {
    private val clock = ScenarioClock()
    private val period = LocalDate.parse("2026-09-18")
    private var service = AdvanceService(capital, clock, period)
    private var advance: Advance? = null
    private var nextStep = 0
    private var signals = emptyList<Signal>()
    private var assessment: JsonObject? = null
    private var requestedPrincipal = 20_000L
    private var riskInputs = RiskInputs(100_000, 0, 100_000, 7)
    private var collection: CollectionResult? = null
    private var statementMismatch = false
    private var postRequests = 0
    private var lookupRequests = 0
    private val events = mutableListOf<StepEvent>()
    private var lastMessage = "Scenario created with isolated database schemas. Choose Next step to begin."

    init {
        seedScenario(capital, bankDatabase)
        capital.install("/db/dashboard.sql")
        bankDatabase.transaction { it.update("CREATE TABLE lab_store_receipts (id TEXT PRIMARY KEY,report_id TEXT NOT NULL,pool_id TEXT NOT NULL,cents BIGINT NOT NULL CHECK(cents>0),currency TEXT NOT NULL)") }
        capital.transaction { c ->
            when (scenario.id) {
                "stale" -> c.update("UPDATE pools SET report_through=?", period.minusDays(1))
                "refund" -> c.update("UPDATE pools SET net_proceeds_cents=70000")
                "liquidity" -> c.update("UPDATE treasury SET cash_cents=10000")
                "fraud" -> { c.update("UPDATE pools SET net_proceeds_cents=400000"); riskInputs = RiskInputs(400_000, 0, 100_000, 7) }
                "cancellations" -> { c.update("UPDATE pools SET net_proceeds_cents=85000"); riskInputs = RiskInputs(100_000, 15_000, 100_000, 7) }
                "public-data" -> {
                    val date = requireNotNull(datasetDate)
                    riskInputs = PublicDataset.inputs(date)
                    val net = PublicDataset.toDemoUsd(PublicDataset.day(date)["netMinor"].asLong)
                    val limit = maxOf(net.coerceAtLeast(0), 1)
                    val cash = maxOf(limit * 2, 100_000)
                    c.update("UPDATE treasury SET cash_cents=?", cash)
                    c.update("UPDATE developers SET outstanding_cents=0,limit_cents=?", limit)
                    c.update("UPDATE pools SET net_proceeds_cents=?,outstanding_cents=0,funded_lifetime_cents=0", net)
                    requestedPrincipal = (net.coerceAtLeast(0) * 8 / 10).coerceAtLeast(1)
                    bankDatabase.transaction { it.update("UPDATE bank_balance SET cents=?", cash) }
                }
            }
        }
        if (scenario.id == "liquidity") bankDatabase.transaction { it.update("UPDATE bank_balance SET cents=10000") }
    }

    @Synchronized fun step(expectedStep: Int): JsonObject {
        require(expectedStep >= 0)
        // Browser retries/double clicks cannot advance twice. A stale step returns the current state.
        if (expectedStep < nextStep) return snapshot()
        require(expectedStep == nextStep && nextStep < scenario.steps.size) { "Step is not available" }
        val title = scenario.steps[nextStep]
        lastMessage = perform(nextStep)
        nextStep++
        val state = state()
        events.add(StepEvent(nextStep, title, clock.instant().toString(), lastMessage, state))
        return snapshot()
    }

    @Synchronized fun snapshot(): JsonObject = JsonObject().apply {
        addProperty("id", id); add("scenario", Gson().toJsonTree(scenario)); addProperty("nextStep", nextStep)
        addProperty("complete", nextStep == scenario.steps.size); addProperty("message", lastMessage)
        addProperty("datasetDate", datasetDate); addProperty("capitalSchema", capital.schema); addProperty("bankSchema", bankDatabase.schema)
        add("assessment", assessment); add("signals", Gson().toJsonTree(signals)); add("riskInputs", Gson().toJsonTree(riskInputs))
        add("collection", Gson().toJsonTree(collection)); add("state", state()); add("events", Gson().toJsonTree(events))
        addProperty("scope", "Local lab · synthetic money · illustrative risk rules · simulated bank")
    }

    private fun perform(step: Int): String {
        if (step == 0) return assess()
        when (scenario.id) {
            "stale", "refund", "fraud", "cancellations", "liquidity" -> return if (step == 1) reserve() else "No payment was authorized. Reservations, bank cash, and ledger remain unchanged."
            "race", "duplicate" -> {
                if (step == 1) return race(scenario.id == "duplicate")
                if (scenario.id == "duplicate" && step == 2) {
                    return try { service.reserve(request().copy(principalCents = 10_000), "duplicate"); error("Expected conflict") }
                    catch (_: IdempotencyConflict) { "Changed principal with the same key was rejected. The original command is unchanged." }
                }
                if (step == if (scenario.id == "duplicate") 3 else 2) return dispatch()
                return replay()
            }
        }
        if (step == 1) return reserve()
        return when (scenario.id) {
            "happy", "public-data" -> if (step == 2) dispatch() else replay()
            "lost-held" -> when (step) {
                2 -> dispatch(BankFault.LOSE_RESPONSE_AFTER_COMMIT)
                3 -> hold(true)
                4 -> recover()
                else -> replay()
            }
            "not-found-held" -> when (step) {
                2 -> dispatch(BankFault.LOSE_RESPONSE_BEFORE_EXECUTION)
                3 -> hold(true)
                4 -> recover()
                5 -> { hold(false); recover() }
                else -> replay()
            }
            "retry" -> when (step) { 2 -> dispatch(BankFault.LOSE_RESPONSE_BEFORE_EXECUTION); 3 -> recover(); else -> replay() }
            "crash" -> when (step) {
                2 -> {
                    withBank { gateway ->
                        try {
                            service.process(requireNotNull(advance).id, object : BankGateway {
                                override fun submit(command: BankCommand): BankResult { gateway.submit(command); throw SimulatedWorkerStop() }
                                override fun lookup(command: BankCommand): BankResult = gateway.lookup(command)
                            })
                        } catch (_: SimulatedWorkerStop) { /* Leave the durable claim unfinished, just as a stopped worker does. */ }
                    }
                    "Bank executed; local posting did not run. The operation remains DISPATCHING with its reservation."
                }
                3 -> { clock.advance(Duration.ofSeconds(31)); recover() }
                else -> replay()
            }
            "rejected" -> if (step == 2) dispatch(BankFault.REJECT_BEFORE_PAYMENT) else replay()
            "hold-before" -> when (step) { 2 -> hold(true); 3 -> dispatch(); else -> replay() }
            "destination" -> when (step) {
                2 -> { capital.transaction { it.update("UPDATE developers SET destination_version='verified-destination-v2'") }; "Verified destination changed. The reserved command still targets v1." }
                3 -> dispatch()
                else -> replay()
            }
            "mismatch" -> if (step == 2) dispatch() else { statementMismatch = true; "Injected a +$1 discrepancy into an independent statement copy. The underlying bank record and ledger are unchanged." }
            "collection", "shortfall", "collection-mismatch" -> if (step == 2) dispatch() else collect()
            else -> error("Unknown scenario")
        }
    }

    private fun assess(): String {
        signals = monitor(riskInputs)
        if (signals.any { it.severity == "HOLD" }) service.setHold("developer-1", true)
        assessment = capital.transaction { c ->
            val snapshot = c.rows("SELECT * FROM pools WHERE id='pool-1'") {
                Snapshot("pool-1", "lab-source-v1", it.getDate("report_through").toLocalDate(), it.getTimestamp("downloaded_at").toInstant(), it.getLong("net_proceeds_cents"), it.getLong("outstanding_cents"))
            }.single()
            decisionRecord("dashboard", evaluate(snapshot, Policy("demo-v1", c.number("SELECT limit_cents FROM developers")), EvaluationContext(clock.instant(), period, ZoneOffset.UTC)))
        }
        return if (signals.any { it.severity == "HOLD" }) "Monitoring placed a risk hold. The arithmetic quote is diagnostic; authorization will enforce the hold."
            else "Calculated quote and freshness reasons. Authorization will recheck capacity, holds, destination, and funding cash inside a transaction."
    }
    private fun request() = AdvanceRequest("developer-1", "pool-1", requestedPrincipal, "verified-destination-v1")
    private fun reserve(): String = try {
        advance = service.reserve(request(), "dashboard-advance")
        "Principal and net cash reserved atomically with the dispatch intent. No bank payment yet."
    } catch (failure: FundingDeclined) { "Reservation declined: ${failure.message}. No payment was created." }
    private fun hold(active: Boolean): String { service.setHold("developer-1", active); return if (active) "New risk hold applied. Unknown bank outcomes retain their reservations." else "Hold cleared. Recovery must still resolve the original bank identity." }
    private fun withBank(fault: BankFault = BankFault.NONE, operation: (HttpBankGateway) -> Unit) {
        FakeBankServer(bankDatabase).use { server ->
            server.nextFault.set(fault)
            try { operation(HttpBankGateway(server.endpoint)) }
            finally { postRequests += server.postRequests.get(); lookupRequests += server.lookupRequests.get() }
        }
    }
    private fun dispatch(fault: BankFault = BankFault.NONE): String {
        val current = advance ?: return "No authorized advance exists. No bank request was sent."
        withBank(fault) { service.process(current.id, it) }
        val result = service.get(current.id)
        return "Operation is ${result.state}. ${result.lastReason ?: "Bank observation and local posting recorded."}"
    }
    private fun recover(): String {
        clock.advance(Duration.ofSeconds(3))
        service = AdvanceService(capital, clock, period)
        return dispatch()
    }
    private fun replay(): String { advance?.let { withBank { gateway -> service.process(it.id, gateway) } }; return "Compared payment identities, amounts, bank evidence, and cash postings. Replaying a terminal operation has no additional effect." }
    private fun race(identical: Boolean): String {
        val ready = CountDownLatch(8); val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 8).map { index -> executor.submit<Advance?> {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                try { AdvanceService(capital, clock, period).reserve(request(), if (identical) "duplicate" else "competitor-$index") }
                catch (_: FundingDeclined) { null }
            } }
            check(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            val results = futures.mapNotNull { it.get(15, TimeUnit.SECONDS) }
            advance = results.first()
            check(results.map { it.id }.toSet().size == 1)
            return "${results.size} of 8 calls returned an operation; ${results.map { it.id }.toSet().size} unique reservation. PostgreSQL enforced the shared limit."
        } finally { executor.shutdownNow() }
    }
    private fun collect(): String {
        val receiptAmount = when (scenario.id) { "shortfall" -> 70_000L; "collection-mismatch" -> 99_500L; else -> 100_000L }
        val reportAmount = if (scenario.id == "shortfall") 70_000L else 100_000L
        bankDatabase.transaction { it.update("INSERT INTO lab_store_receipts(id,report_id,pool_id,cents,currency) VALUES ('receipt-1','report-1','pool-1',?,'USD') ON CONFLICT(id) DO NOTHING", receiptAmount) }
        val receipt = bankDatabase.transaction { c -> c.rows("SELECT * FROM lab_store_receipts WHERE id='receipt-1'") { StoreReceipt(it.getString("id"), it.getString("report_id"), it.getString("pool_id"), it.getLong("cents"), it.getString("currency")) }.single() }
        collection = CollectionService(capital).allocate(receipt, FinalReport("report-1", "pool-1", reportAmount))
        return "${collection!!.status}: ${collection!!.explanation}"
    }

    private fun state(): JsonObject {
        val local = capital.transaction { c -> JsonObject().apply {
            add("treasury", Gson().toJsonTree(c.table("SELECT * FROM treasury")))
            add("developers", Gson().toJsonTree(c.table("SELECT * FROM developers ORDER BY id")))
            add("pools", Gson().toJsonTree(c.table("SELECT * FROM pools ORDER BY id")))
            add("advances", Gson().toJsonTree(c.table("SELECT * FROM advances ORDER BY created_at,id")))
            add("outbox", Gson().toJsonTree(c.table("SELECT * FROM outbox ORDER BY advance_id")))
            add("ledger", Gson().toJsonTree(c.table("SELECT j.posting_key,e.account,e.side,e.cents FROM journals j JOIN ledger_entries e ON e.journal_id=j.id ORDER BY e.id")))
            add("collections", Gson().toJsonTree(c.table("SELECT * FROM lab_collections ORDER BY receipt_id")))
            add("collectionLedger", Gson().toJsonTree(c.table("SELECT receipt_id,account,side,cents FROM lab_collection_entries ORDER BY id")))
        } }
        bankDatabase.transaction { c ->
            local.add("bank", Gson().toJsonTree(c.table("SELECT provider_key,transfer_id,amount_cents,currency,destination,state,submit_count FROM bank_operations ORDER BY created_at")))
            local.addProperty("bankCashCents", c.number("SELECT cents FROM bank_balance WHERE id=1"))
            local.add("storeReceipts", Gson().toJsonTree(c.table("SELECT * FROM lab_store_receipts ORDER BY id")))
        }
        val payments = local.getAsJsonArray("advances").map { element -> val a = element.asJsonObject
            val posted = capital.transaction { it.number("SELECT coalesce(sum(e.cents),0) FROM ledger_entries e JOIN journals j ON j.id=e.journal_id WHERE j.advance_id=? AND e.account='FUNDING_CASH' AND e.side='CREDIT'", UUID.fromString(a["id"].asString)) }
            PaymentEvidence(a["provider_key"].asString, a["bank_transfer_id"]?.takeUnless { it.isJsonNull }?.asString, a["cash_cents"].asLong, a["currency"].asString, a["state"].asString, posted)
        }
        val statement = local.getAsJsonArray("bank").filter { it.asJsonObject["state"].asString == "SETTLED" }.map {
            val b = it.asJsonObject
            StatementLine(b["provider_key"].asString,b["transfer_id"].asString,b["amount_cents"].asLong + if (statementMismatch) 100 else 0,b["currency"].asString)
        }
        local.add("reconciliation", Gson().toJsonTree(reconcile(payments, statement)))
        local.addProperty("statementFaultInjected", statementMismatch)
        local.addProperty("bankPostRequests", postRequests); local.addProperty("bankLookupRequests", lookupRequests)
        local.addProperty("clock", clock.instant().toString())
        return local
    }
}

internal fun Connection.table(sql: String): List<Map<String, Any?>> = rows(sql) { row ->
    (1..row.metaData.columnCount).associate { index ->
        val value = row.getObject(index)
        row.metaData.getColumnLabel(index) to when (value) { null -> null; is Number, is Boolean -> value; else -> value.toString() }
    }
}
