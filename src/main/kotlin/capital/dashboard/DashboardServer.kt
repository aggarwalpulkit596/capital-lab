package capital.dashboard

import capital.config.DatabaseConfig
import capital.config.HttpSettings
import capital.config.RuntimeConfig
import capital.ingestion.PublicDataset
import capital.payments.number
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private class ApiFailure(val status: Int, val code: String, override val message: String) :
    RuntimeException(message)

class DashboardServer(
    private val databaseConfig: DatabaseConfig,
    private val http: HttpSettings = HttpSettings(),
    evidenceDirectory: Path? = Path.of("build/dashboard-runs"),
    private val telemetry: Telemetry = Telemetry(),
) : AutoCloseable {
    constructor(
        jdbcUrl: String,
        dbUser: String,
        dbPassword: String,
        port: Int = HttpSettings().port,
        evidenceDirectory: Path? = Path.of("build/dashboard-runs"),
        telemetry: Telemetry = Telemetry(),
    ) : this(
        DatabaseConfig(jdbcUrl, dbUser, dbPassword),
        HttpSettings(port = port),
        evidenceDirectory,
        telemetry,
    )

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val runs = ConcurrentHashMap<String, LabRun>()
    private val evidence = EvidenceStore(evidenceDirectory)
    private val executor =
        ThreadPoolExecutor(
            http.workerThreads,
            http.workerThreads,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(http.queueCapacity),
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", http.port), http.backlog)
    val address: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/", ::handle)
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        val requestId = UUID.randomUUID().toString()
        val started = System.nanoTime()
        exchange.responseHeaders.set("X-Request-Id", requestId)
        try {
            validateRequest(exchange)
            val path = exchange.requestURI.path
            when {
                path == "/health/live" && exchange.requestMethod == "GET" ->
                    respond(exchange, 200, mapOf("status" to "UP"))
                path == "/health/ready" && exchange.requestMethod == "GET" -> {
                    databaseConfig.database("public").transaction {
                        it.number("SELECT 1")
                    }
                    respond(exchange, 200, mapOf("status" to "READY", "database" to "reachable"))
                }
                path == "/api/metrics" && exchange.requestMethod == "GET" ->
                    respond(exchange, 200, telemetry.snapshot())
                path == "/api/catalog" && exchange.requestMethod == "GET" ->
                    respond(exchange, 200, scenarios)
                path == "/api/dataset" && exchange.requestMethod == "GET" ->
                    respond(exchange, 200, PublicDataset.json)
                path == "/api/runs" && exchange.requestMethod == "GET" -> {
                    val all =
                        evidence.summaries().associateBy { it["id"].toString() }.toMutableMap()
                    runs.values.forEach {
                        all[it.id] =
                            mapOf(
                                "id" to it.id,
                                "title" to it.scenario.title,
                                "archived" to false,
                                "purpose" to it.purpose,
                            )
                    }
                    respond(exchange, 200, all.values.sortedBy { it["id"].toString() })
                }
                path == "/api/runs" && exchange.requestMethod == "POST" -> {
                    val input = body(exchange)
                    val scenario =
                        scenarios.singleOrNull { it.id == input.requiredString("scenario") }
                            ?: throw ApiFailure(
                                400,
                                "UNKNOWN_SCENARIO",
                                "Choose a scenario from the catalog",
                            )
                    val date =
                        if (scenario.id == "public-data")
                            input.requiredString("datasetDate").also { PublicDataset.day(it) }
                        else null
                    val purpose =
                        if (input.has("purpose")) input.requiredString("purpose") else "scenario"
                    require(
                        purpose in setOf("workspace", "scenario") &&
                            (purpose != "workspace" || scenario.id == "happy")
                    ) {
                        "Workspace runs must use the normal advance scenario"
                    }
                    val run = createRun(scenario, date, purpose)
                    val snapshot = run.snapshot()
                    evidence.save(run.id, snapshot)
                    exchange.responseHeaders.set("Location", "/api/runs/${run.id}")
                    respond(exchange, 201, snapshot)
                }
                path.matches(Regex("/api/runs/[a-f0-9]{32}(/step)?")) -> {
                    val id = path.split('/')[3]
                    val run = runs[id]
                    if (path.endsWith("/step") && exchange.requestMethod == "POST") {
                        if (run == null) {
                            if (evidence.read(id) != null)
                                throw ApiFailure(
                                    409,
                                    "ARCHIVED_READ_ONLY",
                                    "This run is archived. Inspect its evidence or start a new isolated scenario.",
                                )
                            throw ApiFailure(404, "RUN_NOT_FOUND", "Run not found")
                        }
                        val step = body(exchange)["expectedStep"]
                        require(
                            step != null && step.isJsonPrimitive && step.asJsonPrimitive.isNumber
                        ) {
                            "expectedStep must be an integer"
                        }
                        val snapshot = run.step(step.asBigDecimal.intValueExact())
                        evidence.save(id, snapshot)
                        respond(exchange, 200, snapshot)
                    } else if (!path.endsWith("/step") && exchange.requestMethod == "GET") {
                        val snapshot =
                            run?.snapshot()
                                ?: evidence.read(id)?.apply { addProperty("archived", true) }
                                ?: throw ApiFailure(404, "RUN_NOT_FOUND", "Run not found")
                        respond(exchange, 200, snapshot)
                    } else throw ApiFailure(405, "METHOD_NOT_ALLOWED", "Method not supported")
                }
                path in setOf("/", "/app.js", "/workspace.js", "/style.css") &&
                    exchange.requestMethod == "GET" -> {
                    val resource = if (path == "/") "/index.html" else path
                    val content =
                        checkNotNull(javaClass.getResource("/dashboard$resource")).readBytes()
                    raw(
                        exchange,
                        200,
                        when (resource) {
                            "/app.js",
                            "/workspace.js" -> "text/javascript"
                            "/style.css" -> "text/css"
                            else -> "text/html"
                        },
                        content,
                    )
                }
                else -> throw ApiFailure(404, "NOT_FOUND", "Not found")
            }
        } catch (failure: ApiFailure) {
            error(exchange, failure.status, failure.code, failure.message, requestId)
        } catch (_: com.google.gson.JsonParseException) {
            error(exchange, 400, "INVALID_JSON", "Invalid JSON", requestId)
        } catch (failure: IllegalArgumentException) {
            error(exchange, 400, "INVALID_REQUEST", failure.message ?: "Invalid request", requestId)
        } catch (_: ArithmeticException) {
            error(exchange, 400, "INVALID_INTEGER", "Expected an integer in range", requestId)
        } catch (_: SQLException) {
            error(
                exchange,
                503,
                "DATABASE_UNAVAILABLE",
                "Database operation could not complete. Refresh this run before retrying the same step.",
                requestId,
            )
        } catch (_: IOException) {
            error(
                exchange,
                503,
                "EVIDENCE_UNAVAILABLE",
                "Evidence could not be saved or read. A step may already have committed; refresh this run before retrying.",
                requestId,
            )
        } catch (_: Exception) {
            error(
                exchange,
                500,
                "INTERNAL_ERROR",
                "Operation failed. Use the request ID to inspect server logs; no automatic retry was performed.",
                requestId,
            )
        } finally {
            telemetry.record(
                requestId,
                exchange.requestMethod,
                route(exchange.requestURI.path),
                exchange.responseCode.takeIf { it > 0 } ?: 500,
                (System.nanoTime() - started) / 1_000_000,
            )
            exchange.close()
        }
    }

    private fun validateRequest(exchange: HttpExchange) {
        val allowedHosts =
            setOf("127.0.0.1:${server.address.port}", "localhost:${server.address.port}")
        if (exchange.requestHeaders.getFirst("Host") !in allowedHosts)
            throw ApiFailure(403, "LOOPBACK_REQUIRED", "Loopback host required")
        if (exchange.requestMethod !in setOf("GET", "POST"))
            throw ApiFailure(405, "METHOD_NOT_ALLOWED", "Method not supported")
        if (exchange.requestMethod == "POST") {
            val origin = exchange.requestHeaders.getFirst("Origin")
            if (
                (origin != null && origin !in allowedHosts.map { "http://$it" }) ||
                    exchange.requestHeaders.getFirst("X-Capital-Lab") != "local-demo"
            )
                throw ApiFailure(
                    403,
                    "ORIGIN_REJECTED",
                    "Use a same-origin request with X-Capital-Lab: local-demo",
                )
            if (
                exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';') !=
                    "application/json"
            )
                throw ApiFailure(
                    415,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "Content-Type must be application/json",
                )
        }
    }

    private fun route(path: String): String =
        when {
            path.matches(Regex("/api/runs/[a-f0-9]{32}/step")) -> "/api/runs/{id}/step"
            path.matches(Regex("/api/runs/[a-f0-9]{32}")) -> "/api/runs/{id}"
            path in
                setOf(
                    "/",
                    "/app.js",
                    "/workspace.js",
                    "/style.css",
                    "/health/live",
                    "/health/ready",
                    "/api/metrics",
                    "/api/catalog",
                    "/api/dataset",
                    "/api/runs",
                ) -> path
            else -> "unmatched"
        }

    @Synchronized
    private fun createRun(scenario: Scenario, date: String?, purpose: String): LabRun {
        if (runs.size >= http.maxActiveRuns)
            throw ApiFailure(
                429,
                "RUN_LIMIT",
                "This local session is limited to ${http.maxActiveRuns} active runs. Restart to archive them and begin another session.",
            )
        val id = UUID.randomUUID().toString().replace("-", "")
        val capital = databaseConfig.database("lab_c_$id")
        val bank = databaseConfig.database("lab_b_$id")
        capital.install("/db/capital.sql")
        capital.install("/db/settlement.sql")
        capital.install("/db/automation.sql")
        bank.install("/db/bank.sql")
        return LabRun(id, scenario, capital, bank, date, purpose).also { runs[id] = it }
    }

    private fun body(exchange: HttpExchange): JsonObject {
        val bytes = exchange.requestBody.use { it.readNBytes(http.maxRequestBytes + 1) }
        if (bytes.size > http.maxRequestBytes)
            throw ApiFailure(
                413,
                "PAYLOAD_TOO_LARGE",
                "Request exceeds ${http.maxRequestBytes} bytes",
            )
        val parsed = JsonParser.parseString(String(bytes, Charsets.UTF_8))
        require(parsed.isJsonObject) { "Expected a JSON object" }
        return parsed.asJsonObject
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "$key must be a string"
        }
        return value.asString
    }

    private fun error(
        exchange: HttpExchange,
        status: Int,
        code: String,
        message: String,
        id: String,
    ) {
        runCatching {
            respond(exchange, status, mapOf("error" to message, "code" to code, "requestId" to id))
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, value: Any) =
        raw(exchange, status, "application/json", gson.toJson(value).toByteArray(Charsets.UTF_8))

    private fun raw(exchange: HttpExchange, status: Int, type: String, bytes: ByteArray) {
        exchange.responseHeaders.set("Content-Type", "$type; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        )
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(1)
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }
}

/**
 * Fails before the port is bound when the demo database is unreachable, so the operator reads one
 * actionable line in the terminal instead of discovering the outage per request in the browser.
 */
private fun requireReachableDatabase(config: DatabaseConfig) {
    try {
        config.database("public").transaction { it.number("SELECT 1") }
    } catch (failure: SQLException) {
        System.err.println("Cannot reach the demo database at ${config.url} as ${config.user}.")
        System.err.println("Start it with: docker compose up -d --wait postgres")
        System.err.println(
            "Override the target with LAB_JDBC_URL, LAB_DB_USER and LAB_DB_PASSWORD."
        )
        System.err.println("Driver reported: ${failure.message}")
        exitProcess(1)
    }
}

fun main() {
    val config =
        try {
            RuntimeConfig.fromEnvironment()
        } catch (failure: IllegalArgumentException) {
            System.err.println("Invalid runtime configuration: ${failure.message}")
            System.err.println("See docs/runbook.md for the supported LAB_* environment variables.")
            exitProcess(2)
        }
    requireReachableDatabase(config.database)
    val server =
        try {
            DashboardServer(config.database, config.http, config.evidenceDirectory)
        } catch (failure: BindException) {
            System.err.println("Port ${config.http.port} on 127.0.0.1 is already in use.")
            System.err.println("Stop the other dashboard, or set LAB_HTTP_PORT to a free port.")
            exitProcess(1)
        }
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    println("Capital Lab dashboard: ${server.address}")
    println(
        "Synthetic money only. Previous server sessions are available as read-only evidence archives."
    )
    println("Stop with Ctrl-C, then stop the database with: docker compose stop postgres")
}
