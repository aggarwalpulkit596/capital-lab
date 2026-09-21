package capital.api

import capital.config.HttpSettings
import capital.payments.Database
import capital.payments.update
import capital.simulation.ScenarioClock
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*
import kotlin.test.Test
import org.junit.jupiter.api.*
import org.testcontainers.postgresql.PostgreSQLContainer

/** The versioned tenant API: authentication, tenancy isolation, scope, and idempotency. */
@Tag("postgres")
@Timeout(60)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiIntegrationTest {
    private val postgres =
        PostgreSQLContainer("postgres:17-alpine")
            .withCommand("postgres", "-c", "fsync=on")
            .withCreateContainerCmdModifier { command ->
                command.hostConfig!!.withPortBindings(
                    PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort(5432))
                )
            }

    private lateinit var database: Database
    private lateinit var server: ApiServer
    private lateinit var clock: ScenarioClock
    private lateinit var writeKey: String
    private lateinit var readKey: String
    private lateinit var operatorKey: String
    private lateinit var otherKey: String
    private val client: HttpClient = HttpClient.newHttpClient()
    private val reportThrough: LocalDate = LocalDate.parse("2026-09-18")

    @BeforeAll fun startPostgres() = postgres.start()

    @AfterAll fun stopPostgres() = postgres.stop()

    @BeforeEach
    fun setup() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val schema = "api_$suffix"
        clock = ScenarioClock()
        database = Database(postgres.jdbcUrl, postgres.username, postgres.password, schema)
        database.install("/db/capital.sql")
        database.install("/db/settlement.sql")
        database.install("/db/automation.sql")
        database.install("/db/api.sql")
        database.transaction { c ->
            c.update("INSERT INTO treasury(id,cash_cents) VALUES (1,?)", 10_000_000)
            listOf("developer-1", "developer-2").forEach { id ->
                c.update(
                    "INSERT INTO developers(id,limit_cents,outstanding_cents,destination_version) VALUES (?,?,0,?)",
                    id,
                    5_000_000,
                    "verified-destination-v1",
                )
            }
            c.update(
                """INSERT INTO pools(id,developer_id,net_proceeds_cents,funded_lifetime_cents,outstanding_cents,
                    report_through,downloaded_at) VALUES ('pool-a','developer-1',?,0,0,?,?)""",
                500_000,
                reportThrough,
                clock.instant(),
            )
            c.update(
                """INSERT INTO pools(id,developer_id,net_proceeds_cents,funded_lifetime_cents,outstanding_cents,
                    report_through,downloaded_at) VALUES ('pool-z','developer-2',?,0,0,?,?)""",
                500_000,
                reportThrough,
                clock.instant(),
            )
        }
        server = ApiServer(database, HttpSettings(port = 0), clock, reportThrough)
        readKey = server.issueKey("developer-1", "read", Scope.READ)
        writeKey = server.issueKey("developer-1", "write", Scope.WRITE)
        operatorKey = server.issueKey("developer-1", "ops", Scope.OPERATOR)
        otherKey = server.issueKey("developer-2", "other", Scope.WRITE)
    }

    @AfterEach fun stopServer() = server.close()

    private fun call(
        method: String,
        path: String,
        key: String? = null,
        body: String? = null,
        idempotencyKey: String? = null,
    ): Pair<Int, JsonObject> {
        val builder = HttpRequest.newBuilder(URI.create(server.address + path))
        key?.let { builder.header("Authorization", "Bearer $it") }
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(
            method,
            if (body == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(body),
        )
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val parsed = JsonParser.parseString(response.body())
        return response.statusCode() to
            if (parsed.isJsonObject) parsed.asJsonObject else JsonObject()
    }

    @Test
    fun `the served spec is the checked-in contract and needs no key`() {
        val (status, body) = call("GET", "/v1/openapi.json")
        assertEquals(200, status)
        assertEquals("3.1.0", body["openapi"].asString)
        assertTrue(body.getAsJsonObject("paths").has("/v1/portfolio"))
    }

    @Test
    fun `every other route requires a key`() {
        val (status, body) = call("GET", "/v1/portfolio")
        assertEquals(401, status)
        assertEquals("UNAUTHENTICATED", body.getAsJsonObject("error")["code"].asString)
    }

    @Test
    fun `an unknown or revoked key is refused`() {
        assertEquals(401, call("GET", "/v1/portfolio", "rck_not-a-real-key").first)
        val doomed = server.issueKey("developer-1", "temporary", Scope.READ)
        assertEquals(200, call("GET", "/v1/portfolio", doomed).first)
        ApiKeys(database, clock).revoke(doomed)
        val (status, body) = call("GET", "/v1/portfolio", doomed)
        assertEquals(401, status)
        assertTrue(body.getAsJsonObject("error")["message"].asString.contains("revoked"))
    }

    @Test
    fun `a read key cannot request an advance`() {
        val (status, body) =
            call(
                "POST",
                "/v1/advances",
                readKey,
                """{"poolId":"pool-a","principalCents":1000}""",
                idempotencyKey = "k1",
            )
        assertEquals(403, status)
        assertEquals("INSUFFICIENT_SCOPE", body.getAsJsonObject("error")["code"].asString)
    }

    @Test
    fun `a developer key cannot assert that a store paid them`() {
        val (status, body) =
            call(
                "POST",
                "/v1/remittances",
                writeKey,
                """{"remittanceId":"r1","store":"apple","developerId":"developer-1",
                    "receivedCents":1000,"lines":[{"poolId":"pool-a","cents":1000}]}""",
            )
        assertEquals(403, status)
        assertEquals("INSUFFICIENT_SCOPE", body.getAsJsonObject("error")["code"].asString)
        assertEquals(
            200,
            call(
                    "POST",
                    "/v1/remittances",
                    operatorKey,
                    """{"remittanceId":"r1","store":"apple","developerId":"developer-1",
                "receivedCents":1000,"lines":[{"poolId":"pool-a","cents":1000}]}""",
                )
                .first,
        )
    }

    @Test
    fun `a key cannot reach another developer's pool`() {
        assertEquals(200, call("GET", "/v1/pools/pool-a/availability", writeKey).first)
        // developer-2's key asking for developer-1's pool must not learn it exists.
        val (status, body) = call("GET", "/v1/pools/pool-a/availability", otherKey)
        assertEquals(404, status)
        assertEquals("NOT_FOUND", body.getAsJsonObject("error")["code"].asString)

        val (advanceStatus, _) =
            call(
                "POST",
                "/v1/advances",
                otherKey,
                """{"poolId":"pool-a","principalCents":1000}""",
                idempotencyKey = "cross-tenant",
            )
        assertEquals(404, advanceStatus)
    }

    @Test
    fun `availability quotes principal fee and net cash with its evidence`() {
        val (status, body) = call("GET", "/v1/pools/pool-a/availability", readKey)
        assertEquals(200, status)
        assertEquals(400_000, body["principalCents"].asLong)
        assertEquals(10_000, body["feeCents"].asLong)
        assertEquals(390_000, body["netCashCents"].asLong)
        assertTrue(body["blockedReason"].isJsonNull)
        assertEquals(
            "early-payouts-v1",
            body.getAsJsonObject("evidence").getAsJsonObject("policy")["version"].asString,
        )
    }

    @Test
    fun `an advance requires an idempotency key and a retry returns the original`() {
        val missing =
            call("POST", "/v1/advances", writeKey, """{"poolId":"pool-a","principalCents":50000}""")
        assertEquals(400, missing.first)
        assertEquals(
            "IDEMPOTENCY_KEY_REQUIRED",
            missing.second.getAsJsonObject("error")["code"].asString,
        )

        val first =
            call(
                "POST",
                "/v1/advances",
                writeKey,
                """{"poolId":"pool-a","principalCents":50000}""",
                idempotencyKey = "same",
            )
        assertEquals(201, first.first)
        assertEquals(50_000, first.second["principalCents"].asLong)
        assertEquals(1_250, first.second["feeCents"].asLong)

        val retry =
            call(
                "POST",
                "/v1/advances",
                writeKey,
                """{"poolId":"pool-a","principalCents":50000}""",
                idempotencyKey = "same",
            )
        assertEquals(first.second["id"].asString, retry.second["id"].asString)

        val conflict =
            call(
                "POST",
                "/v1/advances",
                writeKey,
                """{"poolId":"pool-a","principalCents":60000}""",
                idempotencyKey = "same",
            )
        assertEquals(409, conflict.first)
        assertEquals(
            "IDEMPOTENCY_CONFLICT",
            conflict.second.getAsJsonObject("error")["code"].asString,
        )
    }

    @Test
    fun `a request beyond policy capacity is declined with a reason rather than a crash`() {
        val (status, body) =
            call(
                "POST",
                "/v1/advances",
                writeKey,
                """{"poolId":"pool-a","principalCents":499000}""",
                idempotencyKey = "too-big",
            )
        assertEquals(422, status)
        assertEquals("FUNDING_DECLINED", body.getAsJsonObject("error")["code"].asString)
    }

    @Test
    fun `fractional and non-numeric amounts are refused at the boundary`() {
        listOf(""""principalCents":100.5""", """"principalCents":"lots"""").forEach { field ->
            val (status, body) =
                call(
                    "POST",
                    "/v1/advances",
                    writeKey,
                    """{"poolId":"pool-a",$field}""",
                    idempotencyKey = "bad-${field.hashCode()}",
                )
            assertTrue(status == 400, "expected 400 for $field but got $status")
            assertTrue(body.getAsJsonObject("error")["code"].asString.startsWith("INVALID"))
        }
    }

    @Test
    fun `the portfolio reports the whole relationship`() {
        call(
            "POST",
            "/v1/advances",
            writeKey,
            """{"poolId":"pool-a","principalCents":50000}""",
            idempotencyKey = "p1",
        )
        val (status, body) = call("GET", "/v1/portfolio", readKey)
        assertEquals(200, status)
        assertEquals("developer-1", body["developerId"].asString)
        assertEquals(50_000, body["reservedCents"].asLong)
        assertEquals(5_000_000 - 50_000, body["headroomCents"].asLong)
        assertEquals(1, body.getAsJsonArray("positions").size())
    }

    @Test
    fun `payout policy round-trips and drives a cycle`() {
        val (putStatus, _) =
            call(
                "PUT",
                "/v1/payout-policy",
                writeKey,
                """{"mode":"AUTOMATIC","minimumCents":1000,"cadence":"DAILY"}""",
            )
        assertEquals(200, putStatus)
        assertEquals("AUTOMATIC", call("GET", "/v1/payout-policy", readKey).second["mode"].asString)

        val cycle = call("POST", "/v1/payout-cycles", writeKey)
        assertEquals(200, cycle.first)
        assertEquals("RAN", cycle.second["status"].asString)
        assertEquals(1, cycle.second.getAsJsonArray("decisions").size())

        val repeat = call("POST", "/v1/payout-cycles", writeKey)
        assertEquals("ALREADY_RAN", repeat.second["status"].asString)
    }

    @Test
    fun `an invalid enum names the accepted values`() {
        val (status, body) =
            call(
                "PUT",
                "/v1/payout-policy",
                writeKey,
                """{"mode":"SOMETIMES","minimumCents":1000}""",
            )
        assertEquals(400, status)
        assertTrue(body.getAsJsonObject("error")["message"].asString.contains("MANUAL"))
    }

    @Test
    fun `unknown routes and wrong content types are refused clearly`() {
        assertEquals(404, call("GET", "/v1/nope", readKey).first)
        assertEquals(404, call("GET", "/v2/portfolio", readKey).first)
        val request =
            HttpRequest.newBuilder(URI.create(server.address + "/v1/advances"))
                .header("Authorization", "Bearer $writeKey")
                .header("Idempotency-Key", "k")
                .POST(HttpRequest.BodyPublishers.ofString("not json"))
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(415, response.statusCode())
    }
}
