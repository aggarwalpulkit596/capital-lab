package capital.payments

import capital.EvaluationContext
import capital.Snapshot
import capital.evaluate
import capital.policy.FinancialTerms
import capital.simulation.ScenarioClock
import capital.simulation.seedScenario
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.test.Test
import org.junit.jupiter.api.*
import org.testcontainers.postgresql.PostgreSQLContainer

@Tag("postgres")
@Timeout(30)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservationIntegrationTest {
    private val postgres =
        PostgreSQLContainer("postgres:17-alpine")
            .withCommand("postgres", "-c", "fsync=on")
            .withCreateContainerCmdModifier { command ->
                command.hostConfig!!.withPortBindings(
                    PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort(5432))
                )
            }
    private lateinit var database: Database
    private lateinit var bankDatabase: Database
    private lateinit var server: FakeBankServer
    private lateinit var bank: HttpBankGateway
    private lateinit var clock: ScenarioClock
    private lateinit var service: AdvanceService
    private val request = AdvanceRequest("developer-1", "pool-1", 20_000, "verified-destination-v1")

    @BeforeAll
    fun startPostgres() {
        postgres.start()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun setup() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        database =
            Database(postgres.jdbcUrl, postgres.username, postgres.password, "capital_$suffix")
        bankDatabase =
            Database(postgres.jdbcUrl, postgres.username, postgres.password, "bank_$suffix")
        database.install("/db/capital.sql")
        database.install("/db/settlement.sql")
        bankDatabase.install("/db/bank.sql")
        seedScenario(database, bankDatabase)
        server = FakeBankServer(bankDatabase)
        bank = HttpBankGateway(server.endpoint)
        clock = ScenarioClock()
        service = newService()
    }

    @Test
    fun `injected terms agree across quote reservation bank and ledger and retries retain frozen fee`() {
        val terms =
            FinancialTerms("test-policy-v2", advanceBasisPoints = 7_000, feeBasisPoints = 125)
        val fixture = capital.simulation.DemoFixture()
        val quote =
            evaluate(
                Snapshot(
                    fixture.poolId,
                    "custom-policy",
                    fixture.reportThrough,
                    fixture.downloadedAt,
                    fixture.proceedsCents,
                    fixture.outstandingCents,
                ),
                terms.quotePolicy(fixture.developerLimitCents),
                EvaluationContext(clock.instant(), fixture.expectedPeriod, ZoneOffset.UTC),
            )
        val custom = AdvanceService(database, clock, fixture.expectedPeriod, terms = terms)
        assertEquals(10_000, quote.eligiblePrincipalCents)
        assertFailsWith<FundingDeclined> { custom.reserve(request, "above-custom-limit") }
        val smaller = request.copy(principalCents = quote.eligiblePrincipalCents)
        val reserved = custom.reserve(smaller, "custom-policy")
        assertEquals(quote.feeCents, reserved.feeCents)
        assertEquals(125, reserved.feeCents)
        assertEquals(quote.netCashCents, reserved.cashCents)
        val recorded = database.transaction { c ->
            c.rows("SELECT decision_json FROM advances WHERE id=?", reserved.id) {
                    JsonParser.parseString(it.getString(1)).asJsonObject
                }
                .single()
        }
        assertEquals(terms.version, recorded.getAsJsonObject("policy")["version"].asString)
        // Reconstruct with default terms: replay still returns the original financial command.
        assertEquals(reserved.feeCents, service.reserve(smaller, "custom-policy").feeCents)
        assertTrue(service.process(reserved.id, bank))
        assertEquals(
            9_875,
            bankDatabase.transaction { it.number("SELECT amount_cents FROM bank_operations") },
        )
        assertEquals(
            125,
            database.transaction {
                it.number("SELECT cents FROM ledger_entries WHERE account='DEFERRED_FEE'")
            },
        )
        assertEquals(
            0,
            database.transaction {
                it.number(
                    "SELECT sum(CASE WHEN side='DEBIT' THEN cents ELSE -cents END) FROM ledger_entries"
                )
            },
        )
    }

    @AfterEach
    fun teardown() {
        if (::server.isInitialized) server.close()
    }

    private fun newService() = AdvanceService(database, clock, LocalDate.parse("2026-09-18"))

    private fun number(sql: String): Long = database.transaction { it.number(sql) }

    private fun bankNumber(sql: String): Long = bankDatabase.transaction { it.number(sql) }

    private fun advanceRetry() {
        clock.advance(Duration.ofSeconds(3))
    }

    private fun <T> concurrently(count: Int, operation: (Int) -> T): List<T> {
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(count)
        try {
            val tasks =
                (0 until count).map { index ->
                    executor.submit<T> {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        operation(index)
                    }
                }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            return tasks.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `simultaneous different requests cannot spend the same pool capacity`() {
        val results =
            concurrently(8) { index ->
                runCatching { newService().reserve(request, "request-$index") }
            }
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is FundingDeclined })
        assertEquals(20_000, number("SELECT reserved_cents FROM pools WHERE id='pool-1'"))
        assertEquals(19_500, number("SELECT reserved_cash_cents FROM treasury"))
        assertEquals(1, number("SELECT count(*) FROM advances"))
        assertEquals(1, number("SELECT count(*) FROM outbox"))
    }

    @Test
    fun `simultaneous identical requests return the same persisted operation`() {
        val results = concurrently(8) { newService().reserve(request, "same-request") }
        assertEquals(1, results.map { it.id }.toSet().size)
        assertEquals(1, number("SELECT count(*) FROM advances"))
        assertEquals(1, number("SELECT count(*) FROM outbox"))
        assertEquals(20_000, number("SELECT reserved_cents FROM developers"))
    }

    @Test
    fun `same request key cannot change principal or destination`() {
        val original = service.reserve(request, "key")
        assertFailsWith<IdempotencyConflict> {
            service.reserve(request.copy(principalCents = 10_000), "key")
        }
        assertFailsWith<IdempotencyConflict> {
            service.reserve(request.copy(destinationVersion = "other"), "key")
        }
        assertEquals(original.id, service.reserve(request, "key").id)
        assertEquals(19_500, number("SELECT reserved_cash_cents FROM treasury"))
    }

    @Test
    fun `borrower limit is shared across distinct receivable pools`() {
        database.transaction {
            it.update("UPDATE developers SET limit_cents=80000 WHERE id='developer-1'")
            it.update(
                """INSERT INTO pools(id,developer_id,net_proceeds_cents,report_through,downloaded_at)
                SELECT 'pool-2',developer_id,100000,report_through,downloaded_at FROM pools WHERE id='pool-1'"""
            )
        }
        val results =
            concurrently(2) { index ->
                runCatching {
                    newService()
                        .reserve(
                            request.copy(poolId = "pool-${index + 1}", principalCents = 15_000),
                            "pool-request-$index",
                        )
                }
            }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(15_000, number("SELECT reserved_cents FROM developers"))
    }

    @Test
    fun `funding liquidity is shared across distinct developers`() {
        database.transaction {
            it.update("UPDATE treasury SET cash_cents=20000")
            it.update(
                "INSERT INTO developers(id,limit_cents,destination_version) VALUES ('developer-2',200000,'verified-destination-v1')"
            )
            it.update(
                """INSERT INTO pools(id,developer_id,net_proceeds_cents,report_through,downloaded_at)
                SELECT 'pool-2','developer-2',100000,report_through,downloaded_at FROM pools WHERE id='pool-1'"""
            )
        }
        val results =
            concurrently(2) { index ->
                runCatching {
                    newService()
                        .reserve(
                            request.copy(
                                developerId = "developer-${index + 1}",
                                poolId = "pool-${index + 1}",
                            ),
                            "cash-$index",
                        )
                }
            }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(19_500, number("SELECT reserved_cash_cents FROM treasury"))
    }

    @Test
    fun `lost response preserves capacity and query recovers one executed transfer`() {
        val advance = service.reserve(request, "key")
        server.nextFault.set(BankFault.LOSE_RESPONSE_AFTER_COMMIT)
        assertTimeoutPreemptively(Duration.ofSeconds(8)) { assertTrue(service.processNext(bank)) }
        assertEquals("UNKNOWN", service.get(advance.id).state)
        assertEquals(1, bankNumber("SELECT count(*) FROM bank_operations WHERE state='SETTLED'"))
        assertEquals(80_500, bankNumber("SELECT cents FROM bank_balance"))
        assertEquals(20_000, number("SELECT reserved_cents FROM developers"))
        assertEquals(19_500, number("SELECT reserved_cash_cents FROM treasury"))
        assertEquals(0, number("SELECT count(*) FROM journals"))
        assertFailsWith<FundingDeclined> { service.reserve(request, "second-key") }
        advanceRetry()
        service = newService()
        assertTrue(service.processNext(bank))
        assertEquals("SETTLED", service.get(advance.id).state)
        assertEquals(1, server.postRequests.get())
        assertEquals(1, server.lookupRequests.get())
        assertEquals(0, number("SELECT reserved_cents FROM developers"))
        assertEquals(80_000, number("SELECT outstanding_cents FROM developers"))
        assertEquals(80_000, number("SELECT funded_lifetime_cents FROM pools"))
        assertEquals(80_500, number("SELECT cash_cents FROM treasury"))
        assertEquals(1, number("SELECT count(*) FROM journals"))
        assertEquals(
            0,
            number(
                "SELECT sum(CASE WHEN side='DEBIT' THEN cents ELSE -cents END) FROM ledger_entries"
            ),
        )
        assertFalse(service.process(advance.id, bank))
        assertEquals(1, number("SELECT count(*) FROM journals"))
    }

    @Test
    fun `hold permits recording an already executed transfer using query only`() {
        val advance = service.reserve(request, "key")
        server.nextFault.set(BankFault.LOSE_RESPONSE_AFTER_COMMIT)
        service.processNext(bank)
        service.setHold("developer-1", true)
        advanceRetry()
        service.processNext(bank)
        assertEquals("SETTLED", service.get(advance.id).state)
        assertEquals(1, server.postRequests.get())
        assertEquals(1, server.lookupRequests.get())
    }

    @Test
    fun `hold never resubmits missing transfer and retains both reservations`() {
        val advance = service.reserve(request, "key")
        server.nextFault.set(BankFault.LOSE_RESPONSE_BEFORE_EXECUTION)
        service.processNext(bank)
        assertEquals("UNKNOWN", service.get(advance.id).state)
        service.setHold("developer-1", true)
        advanceRetry()
        service.processNext(bank)
        assertEquals("UNKNOWN", service.get(advance.id).state)
        assertEquals(1, server.postRequests.get())
        assertEquals(1, server.lookupRequests.get())
        assertEquals(0, bankNumber("SELECT count(*) FROM bank_operations"))
        assertEquals(20_000, number("SELECT reserved_cents FROM developers"))
        assertEquals(19_500, number("SELECT reserved_cash_cents FROM treasury"))
    }

    @Test
    fun `missing transfer without hold resubmits the same frozen operation`() {
        val advance = service.reserve(request, "key")
        server.nextFault.set(BankFault.LOSE_RESPONSE_BEFORE_EXECUTION)
        service.processNext(bank)
        advanceRetry()
        service.processNext(bank)
        assertEquals("SETTLED", service.get(advance.id).state)
        assertEquals(2, server.postRequests.get())
        assertEquals(1, bankNumber("SELECT count(*) FROM bank_operations"))
        assertEquals(
            advance.providerKey,
            bankDatabase.transaction {
                it.rows("SELECT provider_key FROM bank_operations") { row -> row.getString(1) }
                    .single()
            },
        )
    }

    @Test
    fun `hold arriving during recovery lookup stops resubmission`() {
        val advance = service.reserve(request, "key")
        server.nextFault.set(BankFault.LOSE_RESPONSE_BEFORE_EXECUTION)
        service.processNext(bank)
        advanceRetry()
        val racingHold =
            object : BankGateway {
                override fun submit(command: BankCommand) = bank.submit(command)

                override fun lookup(command: BankCommand): BankResult {
                    val result = bank.lookup(command)
                    service.setHold("developer-1", true)
                    return result
                }
            }
        service.processNext(racingHold)
        assertEquals("UNKNOWN", service.get(advance.id).state)
        assertEquals(1, server.postRequests.get())
    }

    @Test
    fun `definitive bank rejection releases funds without booking a payment`() {
        val advance = service.reserve(request, "key")
        server.nextFault.set(BankFault.REJECT_BEFORE_PAYMENT)
        service.processNext(bank)
        assertEquals("REJECTED", service.get(advance.id).state)
        assertEquals(0, number("SELECT reserved_cents FROM developers"))
        assertEquals(0, number("SELECT reserved_cash_cents FROM treasury"))
        assertEquals(0, number("SELECT count(*) FROM journals"))
        assertEquals(100_000, bankNumber("SELECT cents FROM bank_balance"))
    }

    @Test
    fun `new hold cancels a provably undispatched request`() {
        val advance = service.reserve(request, "key")
        service.setHold("developer-1", true)
        assertFalse(service.processNext(bank))
        assertEquals("CANCELED", service.get(advance.id).state)
        assertEquals(0, server.postRequests.get())
        assertEquals(0, number("SELECT reserved_cash_cents FROM treasury"))
    }

    @Test
    fun `changed destination cancels undispatched frozen payment rather than rerouting`() {
        val advance = service.reserve(request, "key")
        database.transaction {
            it.update("UPDATE developers SET destination_version='verified-destination-v2'")
        }
        service.processNext(bank)
        assertEquals("CANCELED", service.get(advance.id).state)
        assertEquals("verified-destination-v1", service.get(advance.id).destinationVersion)
        assertEquals(0, server.postRequests.get())
    }

    @Test
    fun `stale source and wrong developer create no reservation or outbox command`() {
        database.transaction { it.update("UPDATE pools SET report_through = report_through - 1") }
        assertFailsWith<FundingDeclined> { service.reserve(request, "stale") }
        assertFailsWith<FundingDeclined> {
            service.reserve(request.copy(developerId = "wrong-developer"), "wrong-owner")
        }
        assertEquals(0, number("SELECT count(*) FROM advances"))
        assertEquals(0, number("SELECT count(*) FROM outbox"))
        assertEquals(0, number("SELECT reserved_cash_cents FROM treasury"))
    }

    @Test
    fun `partial repayment snapshot cannot recycle lifetime origination capacity`() {
        database.transaction {
            it.update(
                "UPDATE pools SET settled_proceeds_cents=50000,funded_lifetime_cents=80000,outstanding_cents=30000"
            )
            it.update("UPDATE developers SET outstanding_cents=30000")
        }
        assertFailsWith<FundingDeclined> {
            service.reserve(request.copy(principalCents = 10_000), "refinance")
        }
        assertEquals(0, number("SELECT count(*) FROM advances"))
    }

    @Test
    fun `bank idempotency prevents repeated debit and rejects changed payload`() {
        val advance = service.reserve(request, "key")
        val first = bank.submit(advance.command())
        assertIs<BankResult.Settled>(first)
        assertEquals(first, bank.submit(advance.command()))
        assertIs<BankResult.Unknown>(bank.submit(advance.command().copy(amountCents = 1)))
        assertEquals(1, bankNumber("SELECT count(*) FROM bank_operations"))
        assertEquals(80_500, bankNumber("SELECT cents FROM bank_balance"))
    }

    @Test
    fun `separate JVM crash after bank commit recovers the same transfer`() {
        val advance = service.reserve(request, "crash")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val log = Files.createTempFile("capital-crash-worker", ".log")
        val builder =
            ProcessBuilder(
                java,
                "-cp",
                checkNotNull(System.getProperty("lab.test.classpath")),
                "capital.payments.CrashWorkerKt",
            )
        builder
            .environment()
            .putAll(
                mapOf(
                    "LAB_JDBC_URL" to postgres.jdbcUrl,
                    "LAB_DB_USER" to postgres.username,
                    "LAB_DB_PASSWORD" to postgres.password,
                    "LAB_SCHEMA" to database.schema,
                    "LAB_BANK_URI" to server.endpoint.toString(),
                    "LAB_ADVANCE_ID" to advance.id.toString(),
                    "LAB_NOW" to clock.instant().toString(),
                )
            )
        val process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Crash worker timed out")
            assertEquals(23, process.exitValue(), Files.readString(log))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        assertEquals("DISPATCHING", service.get(advance.id).state)
        assertEquals(1, bankNumber("SELECT count(*) FROM bank_operations"))
        assertEquals(0, number("SELECT count(*) FROM journals"))
        clock.advance(Duration.ofSeconds(31))
        service = newService()
        assertTrue(service.processNext(bank))
        assertEquals("SETTLED", service.get(advance.id).state)
        assertEquals(1, server.postRequests.get())
        assertEquals(1, number("SELECT count(*) FROM journals"))
    }

    @Test
    fun `only one worker holds the active dispatch lease`() {
        val advance = service.reserve(request, "lease")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val pausedBank =
            object : BankGateway {
                override fun submit(command: BankCommand): BankResult {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return bank.submit(command)
                }

                override fun lookup(command: BankCommand) = bank.lookup(command)
            }
        try {
            val first = executor.submit<Boolean> { service.process(advance.id, pausedBank) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(newService().process(advance.id, bank))
            // This write completes while the bank call is blocked: no DB lock spans that call.
            newService().setHold("developer-1", true)
            release.countDown()
            assertTrue(first.get(10, TimeUnit.SECONDS))
            assertEquals(1, server.postRequests.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `expired worker cannot overwrite a newer settled observation`() {
        val advance = service.reserve(request, "fencing")
        val paid = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val slowBank =
            object : BankGateway {
                override fun submit(command: BankCommand): BankResult {
                    assertIs<BankResult.Settled>(bank.submit(command))
                    paid.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return BankResult.Unknown("LATE_RESPONSE")
                }

                override fun lookup(command: BankCommand) = bank.lookup(command)
            }
        try {
            val oldWorker = executor.submit<Boolean> { service.process(advance.id, slowBank) }
            assertTrue(paid.await(5, TimeUnit.SECONDS))
            clock.advance(Duration.ofSeconds(31))
            assertTrue(newService().process(advance.id, bank))
            release.countDown()
            oldWorker.get(10, TimeUnit.SECONDS)
            assertEquals("SETTLED", service.get(advance.id).state)
            assertEquals(1, number("SELECT count(*) FROM journals"))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed local posting rolls back balances and remains recoverable`() {
        val advance = service.reserve(request, "posting-failure")
        database.transaction { connection ->
            connection.createStatement().use {
                it.execute(
                    """
                CREATE FUNCTION fail_demo_posting() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'injected posting failure'; END; ${'$'}${'$'};
                CREATE TRIGGER injected_failure BEFORE INSERT ON journals FOR EACH ROW EXECUTE FUNCTION fail_demo_posting();
            """
                )
            }
        }
        assertFailsWith<SQLException> { service.processNext(bank) }
        assertEquals("DISPATCHING", service.get(advance.id).state)
        assertEquals(20_000, number("SELECT reserved_cents FROM developers"))
        assertEquals(60_000, number("SELECT outstanding_cents FROM developers"))
        assertEquals(100_000, number("SELECT cash_cents FROM treasury"))
        assertEquals(0, number("SELECT count(*) FROM journals"))
        database.transaction {
            it.createStatement().use { statement ->
                statement.execute("DROP TRIGGER injected_failure ON journals")
            }
        }
        clock.advance(Duration.ofSeconds(31))
        assertTrue(service.processNext(bank))
        assertEquals("SETTLED", service.get(advance.id).state)
        assertEquals(1, server.postRequests.get())
    }

    @Test
    fun `database rejects unbalanced journals and edits to posted history or frozen commands`() {
        val advance = service.reserve(request, "ledger")
        assertFailsWith<SQLException> {
            database.transaction { connection ->
                val journal = UUID.randomUUID()
                connection.update(
                    "INSERT INTO journals(id,advance_id,posting_key,created_at) VALUES (?,?,?,?)",
                    journal,
                    advance.id,
                    "invalid",
                    clock.instant(),
                )
                connection.update(
                    "INSERT INTO ledger_entries(journal_id,account,currency,side,cents) VALUES (?,'ADVANCE_RECEIVABLE','USD','DEBIT',20000)",
                    journal,
                )
            }
        }
        assertEquals(0, number("SELECT count(*) FROM journals"))
        service.processNext(bank)
        assertFailsWith<SQLException> {
            database.transaction { it.update("UPDATE ledger_entries SET cents=1") }
        }
        assertFailsWith<SQLException> {
            database.transaction { it.update("UPDATE advances SET destination_version='changed'") }
        }
        assertFailsWith<SQLException> {
            database.transaction { connection ->
                connection.update(
                    """INSERT INTO ledger_entries(journal_id,account,currency,side,cents)
                    SELECT id,'ADVANCE_RECEIVABLE','USD','DEBIT',1 FROM journals"""
                )
            }
        }
        assertEquals(3, number("SELECT count(*) FROM ledger_entries"))
    }
}
