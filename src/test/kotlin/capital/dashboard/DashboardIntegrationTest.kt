package capital.dashboard

import capital.collections.*
import capital.ingestion.PublicDataset
import capital.payments.*
import capital.simulation.ScenarioClock
import capital.simulation.seedScenario
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer

@Tag("postgres")
@Timeout(60)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardIntegrationTest {
    private val postgres = PostgreSQLContainer("postgres:17-alpine")
    private lateinit var server: DashboardServer
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun start() {
        postgres.start()
        server = DashboardServer(postgres.jdbcUrl, postgres.username, postgres.password, 0, null)
    }

    @AfterAll
    fun stop() {
        if (::server.isInitialized) server.close()
        postgres.stop()
    }

    private fun request(
        path: String,
        body: String? = null,
        origin: String? = null,
        header: Boolean = true,
    ): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder(URI(server.address + path))
                .timeout(java.time.Duration.ofSeconds(40))
        if (body != null) {
            builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            if (header) builder.header("X-Capital-Lab", "local-demo")
        }
        origin?.let { builder.header("Origin", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(response: HttpResponse<String>): JsonObject {
        assertTrue(response.statusCode() in 200..299, response.body())
        return JsonParser.parseString(response.body()).asJsonObject
    }

    private fun create(id: String) =
        json(request("/api/runs", """{"scenario":"$id","datasetDate":"2011-01-10"}"""))

    private fun next(run: JsonObject) =
        json(
            request(
                "/api/runs/${run["id"].asString}/step",
                """{"expectedStep":${run["nextStep"].asInt}}""",
            )
        )

    private fun amount(state: JsonObject, table: String, field: String) =
        state.getAsJsonArray(table)[0].asJsonObject[field].asLong

    @TestFactory
    fun `every advertised scenario executes with expected financial outcomes`(): List<DynamicTest> =
        scenarios.map { scenario ->
            DynamicTest.dynamicTest(scenario.id) {
                var run = create(scenario.id)
                while (!run["complete"].asBoolean) {
                    run = next(run)
                    if (scenario.id == "not-found-held" && run["nextStep"].asInt == 5) {
                        val state = run.getAsJsonObject("state")
                        assertEquals(20_000, amount(state, "developers", "reserved_cents"))
                        assertEquals(1, state["bankPostRequests"].asInt)
                        assertEquals(1, state["bankLookupRequests"].asInt)
                        assertTrue(state.getAsJsonArray("bank").isEmpty)
                    }
                    if (scenario.id == "crash" && run["nextStep"].asInt == 3) {
                        assertEquals(
                            "DISPATCHING",
                            run["state"]
                                .asJsonObject["advances"]
                                .asJsonArray[0]
                                .asJsonObject["state"]
                                .asString,
                        )
                        assertTrue(run["state"].asJsonObject["ledger"].asJsonArray.isEmpty)
                    }
                }
                val state = run.getAsJsonObject("state")
                assertEquals(0, amount(state, "developers", "reserved_cents"))
                assertEquals(0, amount(state, "treasury", "reserved_cash_cents"))
                assertEquals(scenario.steps.size, run.getAsJsonArray("events").size())
                val blocked =
                    scenario.id in setOf("stale", "refund", "fraud", "cancellations", "liquidity")
                if (blocked) {
                    assertTrue(state.getAsJsonArray("advances").isEmpty)
                    assertTrue(state.getAsJsonArray("bank").isEmpty)
                    assertTrue(state.getAsJsonArray("ledger").isEmpty)
                } else if (scenario.id == "public-data") {
                    assertEquals("2011-01-10", run["datasetDate"].asString)
                    val decision = run.getAsJsonObject("assessment")
                    assertEquals(
                        PublicDataset.toDemoUsd(PublicDataset.day("2011-01-10")["netMinor"].asLong),
                        decision["snapshot"].asJsonObject["netProceedsCents"].asLong,
                    )
                    assertEquals(
                        "SETTLED",
                        state["advances"].asJsonArray[0].asJsonObject["state"].asString,
                    )
                } else if (scenario.id in setOf("rejected", "hold-before", "destination")) {
                    assertEquals(
                        if (scenario.id == "rejected") "REJECTED" else "CANCELED",
                        state["advances"].asJsonArray[0].asJsonObject["state"].asString,
                    )
                    assertEquals(100_000, state["bankCashCents"].asLong)
                    assertTrue(state.getAsJsonArray("ledger").isEmpty)
                } else {
                    assertEquals(1, state.getAsJsonArray("advances").size())
                    assertEquals(1, state.getAsJsonArray("bank").size())
                    assertEquals(80_500, state["bankCashCents"].asLong)
                    assertEquals(3, state.getAsJsonArray("ledger").size())
                    assertEquals(
                        "SETTLED",
                        state["advances"].asJsonArray[0].asJsonObject["state"].asString,
                    )
                    assertEquals(
                        if (scenario.id == "mismatch") "AMOUNT_OR_CURRENCY_MISMATCH" else "MATCHED",
                        state["reconciliation"].asJsonArray[0].asJsonObject["status"].asString,
                    )
                    if (scenario.id == "lost-held") {
                        assertEquals(1, state["bankPostRequests"].asInt)
                        assertEquals(1, state["bankLookupRequests"].asInt)
                    }
                    if (scenario.id in setOf("collection", "shortfall")) {
                        assertEquals(
                            if (scenario.id == "collection") 0 else 10_000,
                            amount(state, "developers", "outstanding_cents"),
                        )
                        assertEquals(80_000, amount(state, "pools", "funded_lifetime_cents"))
                        assertEquals(1, state["collections"].asJsonArray.size())
                        assertEquals(
                            if (scenario.id == "collection") 20_000 else 0,
                            amount(state, "collections", "residual_cents"),
                        )
                        assertEquals(
                            80_500,
                            amount(state, "treasury", "cash_cents"),
                        ) // no premature sweep
                    } else assertEquals(80_000, amount(state, "developers", "outstanding_cents"))
                    if (scenario.id == "collection-mismatch") {
                        assertTrue(state["collections"].asJsonArray.isEmpty)
                        assertEquals("UNAPPLIED", run["collection"].asJsonObject["status"].asString)
                    }
                }
            }
        }

    @Test
    fun `HTTP input validation origin checks and step retries do not create extra effects`() {
        assertEquals(
            403,
            request("/api/runs", """{"scenario":"happy"}""", origin = "https://example.com")
                .statusCode(),
        )
        assertEquals(
            403,
            request("/api/runs", """{"scenario":"happy"}""", header = false).statusCode(),
        )
        assertEquals(400, request("/api/runs", "[]").statusCode())
        assertEquals(400, request("/api/runs", "{").statusCode())
        assertEquals(
            400,
            request("/api/runs", """{"scenario":"public-data","datasetDate":"bad"}""").statusCode(),
        )
        assertEquals(413, request("/api/runs", "x".repeat(8193)).statusCode())
        var run = create("happy")
        run = next(run)
        run = next(run)
        val retry = json(request("/api/runs/${run["id"].asString}/step", """{"expectedStep":1}"""))
        assertEquals(2, retry["nextStep"].asInt)
        assertEquals(1, retry["state"].asJsonObject["advances"].asJsonArray.size())
        assertEquals(
            400,
            request("/api/runs/${run["id"].asString}/step", """{"expectedStep":99}""").statusCode(),
        )
        assertEquals(
            400,
            request("/api/runs/${run["id"].asString}/step", """{"expectedStep":2.5}""")
                .statusCode(),
        )
    }

    @Test
    fun `collection duplicate races post once and mismatches never consume principal`() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val capital =
            Database(postgres.jdbcUrl, postgres.username, postgres.password, "test_c_$suffix")
        val bank =
            Database(postgres.jdbcUrl, postgres.username, postgres.password, "test_b_$suffix")
        capital.install("/db/capital.sql")
        capital.install("/db/settlement.sql")
        capital.install("/db/automation.sql")
        capital.install("/db/dashboard.sql")
        bank.install("/db/bank.sql")
        seedScenario(capital, bank)
        val service = CollectionService(capital)
        val receipt = StoreReceipt("receipt", "report", "pool-1", 100_000)
        val report = FinalReport("report", "pool-1", 100_000)
        assertEquals("UNAPPLIED", service.allocate(receipt.copy(currency = "GBP"), report).status)
        assertEquals("UNAPPLIED", service.allocate(receipt.copy(cents = 99_500), report).status)
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val go = CountDownLatch(1)
        val results =
            try {
                val futures =
                    (0 until 4).map {
                        executor.submit<CollectionResult> {
                            ready.countDown()
                            check(go.await(5, TimeUnit.SECONDS))
                            service.allocate(receipt, report)
                        }
                    }
                check(ready.await(5, TimeUnit.SECONDS))
                go.countDown()
                futures.map { it.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
        assertEquals(1, results.count { it.status == "ALLOCATED" })
        assertEquals(3, results.count { it.status == "ALREADY_ALLOCATED" })
        capital.transaction { c ->
            assertEquals(1, c.number("SELECT count(*) FROM lab_collections"))
            assertEquals(0, c.number("SELECT outstanding_cents FROM developers"))
            assertEquals(60_000, c.number("SELECT funded_lifetime_cents FROM pools"))
            assertEquals(40_000, c.number("SELECT residual_cents FROM lab_collections"))
        }
        assertFailsWith<IllegalArgumentException> {
            service.allocate(receipt.copy(id = "other"), report)
        }
        assertFailsWith<SQLException> {
            capital.transaction { it.update("UPDATE lab_collection_entries SET cents=1") }
        }
        assertFailsWith<SQLException> {
            capital.transaction {
                it.update(
                    "INSERT INTO lab_collection_entries(receipt_id,account,side,cents) VALUES ('receipt','COLLECTION_CASH','DEBIT',1)"
                )
            }
        }
        assertFailsWith<FundingDeclined> {
            AdvanceService(capital, ScenarioClock(), java.time.LocalDate.parse("2026-09-18"))
                .reserve(
                    AdvanceRequest("developer-1", "pool-1", 100, "verified-destination-v1"),
                    "after-close",
                )
        }
    }

    @Test
    fun `liveness readiness and error envelopes carry request identities`() {
        val live = request("/health/live")
        assertEquals(200, live.statusCode())
        assertTrue(live.headers().firstValue("X-Request-Id").isPresent)
        assertEquals("READY", json(request("/health/ready"))["status"].asString)
        val invalid = request("/api/runs", "[]")
        val error = JsonParser.parseString(invalid.body()).asJsonObject
        assertEquals("INVALID_REQUEST", error["code"].asString)
        assertEquals(
            invalid.headers().firstValue("X-Request-Id").get(),
            error["requestId"].asString,
        )
        val metrics = json(request("/api/metrics"))
        assertTrue(metrics["requestsCompleted"].asLong > 0)
    }

    @Test
    fun `restart retains read-only run evidence and never resumes a payment`(
        @org.junit.jupiter.api.io.TempDir root: java.nio.file.Path
    ) {
        fun call(base: String, path: String, body: String? = null): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI(base + path))
            if (body != null)
                builder
                    .header("Content-Type", "application/json")
                    .header("X-Capital-Lab", "local-demo")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        lateinit var run: JsonObject
        DashboardServer(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
                0,
                root,
                Telemetry {},
            )
            .use { first ->
                run =
                    json(
                        call(
                            first.address,
                            "/api/runs",
                            """{"scenario":"happy","purpose":"workspace"}""",
                        )
                    )
                run =
                    json(
                        call(
                            first.address,
                            "/api/runs/${run["id"].asString}/step",
                            """{"expectedStep":0}""",
                        )
                    )
                run =
                    json(
                        call(
                            first.address,
                            "/api/runs/${run["id"].asString}/step",
                            """{"expectedStep":1}""",
                        )
                    )
                assertEquals(
                    20_000,
                    amount(run["state"].asJsonObject, "developers", "reserved_cents"),
                )
            }
        DashboardServer(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
                0,
                root,
                Telemetry {},
            )
            .use { second ->
                val archived = json(call(second.address, "/api/runs/${run["id"].asString}"))
                assertTrue(archived["archived"].asBoolean)
                assertEquals("workspace", archived["purpose"].asString)
                assertEquals(2, archived["nextStep"].asInt)
                assertEquals(0, archived["state"].asJsonObject["bankPostRequests"].asInt)
                val attempt =
                    call(
                        second.address,
                        "/api/runs/${run["id"].asString}/step",
                        """{"expectedStep":2}""",
                    )
                assertEquals(409, attempt.statusCode())
                assertEquals(
                    "ARCHIVED_READ_ONLY",
                    JsonParser.parseString(attempt.body()).asJsonObject["code"].asString,
                )
            }
    }

    @Test
    fun `schema installation is serialized repeatable and rejects checksum drift`() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val db =
            Database(postgres.jdbcUrl, postgres.username, postgres.password, "migration_$suffix")
        val executor = Executors.newFixedThreadPool(4)
        try {
            (0 until 4)
                .map { executor.submit { db.install("/db/capital.sql") } }
                .forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, db.transaction { it.number("SELECT count(*) FROM schema_migrations") })
        db.transaction { it.update("UPDATE schema_migrations SET sha256='changed'") }
        assertFailsWith<IllegalStateException> { db.install("/db/capital.sql") }
    }

    @Test
    fun `database lock timeout bounds competing writers without changing balances`() {
        val schema = "timeouts_" + UUID.randomUUID().toString().replace("-", "")
        val db = Database(postgres.jdbcUrl, postgres.username, postgres.password, schema)
        db.install("/db/capital.sql")
        db.transaction { it.update("INSERT INTO treasury(id,cash_cents) VALUES (1,100000)") }
        db.connection().use { holder ->
            holder.autoCommit = false
            holder.number("SELECT cash_cents FROM treasury WHERE id=1 FOR UPDATE")
            val competing =
                Database(postgres.jdbcUrl, postgres.username, postgres.password, schema, 50, 1000)
            val failure =
                assertFailsWith<SQLException> {
                    competing.transaction { it.update("UPDATE treasury SET cash_cents=0") }
                }
            assertEquals("55P03", failure.sqlState)
            holder.rollback()
        }
        assertEquals(100_000, db.transaction { it.number("SELECT cash_cents FROM treasury") })
    }
}
