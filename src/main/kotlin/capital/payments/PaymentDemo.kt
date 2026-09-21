package capital.payments

import capital.config.RuntimeConfig
import capital.simulation.DemoFixture
import capital.simulation.ScenarioClock
import capital.simulation.seedScenario
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

fun runPaymentScenario(capital: Database, bank: Database): JsonObject {
    seedScenario(capital, bank)
    val clock = ScenarioClock()
    val expectedPeriod = DemoFixture().expectedPeriod
    var service = AdvanceService(capital, clock, expectedPeriod)
    val advance =
        service.reserve(
            AdvanceRequest("developer-1", "pool-1", 20_000, "verified-destination-v1"),
            "demo-advance-1",
        )
    val timeline = JsonArray()
    fun record(label: String) {
        timeline.add(
            JsonObject().apply {
                addProperty("step", label)
                addProperty("advanceId", advance.id.toString())
                addProperty("applicationState", service.get(advance.id).state)
                addProperty(
                    "reservedPrincipalCents",
                    capital.transaction {
                        it.number("SELECT reserved_cents FROM developers WHERE id='developer-1'")
                    },
                )
                addProperty(
                    "reservedCashCents",
                    capital.transaction {
                        it.number("SELECT reserved_cash_cents FROM treasury WHERE id=1")
                    },
                )
                addProperty(
                    "bankTransfersExecuted",
                    bank.transaction {
                        it.number("SELECT count(*) FROM bank_operations WHERE state='SETTLED'")
                    },
                )
                addProperty(
                    "bankCashCents",
                    bank.transaction { it.number("SELECT cents FROM bank_balance WHERE id=1") },
                )
                addProperty(
                    "fundingJournals",
                    capital.transaction { it.number("SELECT count(*) FROM journals") },
                )
            }
        )
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
        addProperty(
            "scope",
            "Synthetic USD money; independent PostgreSQL transactions and loopback HTTP; no real bank",
        )
        addProperty("capitalSchema", capital.schema)
        addProperty("bankSchema", bank.schema)
        add("timeline", timeline)
    }
}

fun main() {
    val config = RuntimeConfig.fromEnvironment()
    val suffix = UUID.randomUUID().toString().replace("-", "")
    val capital = config.database.database("capital_$suffix")
    val bank = config.database.database("bank_$suffix")
    capital.install("/db/capital.sql")
    bank.install("/db/bank.sql")
    val output =
        GsonBuilder().setPrettyPrinting().create().toJson(runPaymentScenario(capital, bank))
    Files.createDirectories(Path.of("build"))
    Files.writeString(Path.of("build/payment-demo.json"), output)
    println(output)
}
