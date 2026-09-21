package capital.payments

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** A test-only separate JVM that dies after the bank paid and before the local commit. */
fun main() {
    fun env(name: String) = checkNotNull(System.getenv(name))
    val database =
        Database(env("LAB_JDBC_URL"), env("LAB_DB_USER"), env("LAB_DB_PASSWORD"), env("LAB_SCHEMA"))
    val http = HttpBankGateway(URI(env("LAB_BANK_URI")))
    val bank =
        object : BankGateway {
            override fun submit(command: BankCommand): BankResult {
                check(http.submit(command) is BankResult.Settled)
                Runtime.getRuntime().halt(23)
                error("unreachable")
            }

            override fun lookup(command: BankCommand): BankResult = http.lookup(command)
        }
    val clock = Clock.fixed(Instant.parse(env("LAB_NOW")), ZoneOffset.UTC)
    AdvanceService(database, clock, LocalDate.parse("2026-09-18"))
        .process(UUID.fromString(env("LAB_ADVANCE_ID")), bank)
    error("Worker should have halted")
}
