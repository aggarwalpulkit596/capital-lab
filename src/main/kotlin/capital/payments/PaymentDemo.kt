package capital.payments

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Only for repeatable demonstrations and tests, not a production clock. */
class ScenarioClock(initial: Instant = Instant.parse("2026-09-19T18:05:00Z")) : Clock() {
    private val current = AtomicReference(initial)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
    override fun instant(): Instant = current.get()
    fun advance(duration: Duration) { current.updateAndGet { it.plus(duration) } }
}

fun seedScenario(capital: Database, bank: Database) {
    capital.transaction { connection ->
        connection.update("INSERT INTO treasury(id,cash_cents) VALUES (1,100000)")
        connection.update("INSERT INTO developers(id,limit_cents,outstanding_cents,destination_version) VALUES ('developer-1',200000,60000,'verified-destination-v1')")
        connection.update("""INSERT INTO pools(id,developer_id,net_proceeds_cents,funded_lifetime_cents,outstanding_cents,report_through,downloaded_at)
            VALUES ('pool-1','developer-1',100000,60000,60000,?,?)""", LocalDate.parse("2026-09-18"), Instant.parse("2026-09-19T18:00:00Z"))
    }
    bank.transaction { it.update("INSERT INTO bank_balance(id,cents) VALUES (1,100000)") }
}

fun runPaymentScenario(capital: Database, bank: Database): JsonObject {
    seedScenario(capital, bank)
    val clock = ScenarioClock()
    val expectedPeriod = LocalDate.parse("2026-09-18")
    var service = AdvanceService(capital, clock, expectedPeriod)
    val advance = service.reserve(AdvanceRequest("developer-1", "pool-1", 20_000, "verified-destination-v1"), "demo-advance-1")
    val timeline = JsonArray()
    fun record(label: String) {
        timeline.add(JsonObject().apply {
            addProperty("step", label)
            addProperty("advanceId", advance.id.toString())
            addProperty("applicationState", service.get(advance.id).state)
            addProperty("reservedPrincipalCents", capital.transaction { it.number("SELECT reserved_cents FROM developers WHERE id='developer-1'") })
            addProperty("reservedCashCents", capital.transaction { it.number("SELECT reserved_cash_cents FROM treasury WHERE id=1") })
            addProperty("bankTransfersExecuted", bank.transaction { it.number("SELECT count(*) FROM bank_operations WHERE state='SETTLED'") })
            addProperty("bankCashCents", bank.transaction { it.number("SELECT cents FROM bank_balance WHERE id=1") })
            addProperty("fundingJournals", capital.transaction { it.number("SELECT count(*) FROM journals") })
        })
    }
    FakeBankServer(bank).use { server ->
        val gateway = HttpBankGateway(server.endpoint)
        record("RESERVED")
        server.nextFault.set(BankFault.LOSE_RESPONSE_AFTER_COMMIT)
        check(service.processNext(gateway))
        check(service.get(advance.id).state == "UNKNOWN")
        record("BANK_PAID_BUT_HTTP_RESPONSE_WAS_TRUNCATED")
        service.setHold("developer-1", true)
        clock.advance(Duration.ofSeconds(3))
        service = AdvanceService(capital, clock, expectedPeriod)
        check(service.processNext(gateway))
        check(service.get(advance.id).state == "SETTLED")
        check(server.postRequests.get() == 1 && server.lookupRequests.get() == 1)
        record("NEW_SERVICE_RECOVERS_BY_QUERY_DURING_HOLD")
        check(!service.processNext(gateway))
        record("REPLAY_HAS_NO_FINANCIAL_EFFECT")
    }
    return JsonObject().apply {
        addProperty("scope", "Synthetic USD money; independent PostgreSQL transactions and loopback HTTP; no real bank")
        addProperty("capitalSchema", capital.schema)
        addProperty("bankSchema", bank.schema)
        add("timeline", timeline)
    }
}

fun main() {
    val url = System.getenv("LAB_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:55432/capital_lab"
    val user = System.getenv("LAB_DB_USER") ?: "capital_lab"
    val password = System.getenv("LAB_DB_PASSWORD") ?: "local_demo_only"
    val suffix = UUID.randomUUID().toString().replace("-", "")
    val capital = Database(url, user, password, "capital_$suffix")
    val bank = Database(url, user, password, "bank_$suffix")
    capital.install("/db/capital.sql")
    bank.install("/db/bank.sql")
    val output = GsonBuilder().setPrettyPrinting().create().toJson(runPaymentScenario(capital, bank))
    Files.createDirectories(Path.of("build"))
    Files.writeString(Path.of("build/payment-demo.json"), output)
    println(output)
}
