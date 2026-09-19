package capital.dashboard

import capital.payments.Database
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class DashboardServer(
    private val jdbcUrl: String,
    private val dbUser: String,
    private val dbPassword: String,
    port: Int = 8080,
    private val evidenceDirectory: Path? = Path.of("build/dashboard-runs"),
) : AutoCloseable {
    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val runs = ConcurrentHashMap<String, LabRun>()
    private val executor = Executors.newFixedThreadPool(4)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 32)
    val address: String get() = "http://127.0.0.1:${server.address.port}"
    init { server.executor = executor; server.createContext("/", ::handle); server.start() }

    private fun handle(exchange: HttpExchange) {
        try {
            val host = exchange.requestHeaders.getFirst("Host")
            if (host !in setOf("127.0.0.1:${server.address.port}", "localhost:${server.address.port}")) { respond(exchange, 403, mapOf("error" to "Loopback host required")); return }
            if (exchange.requestMethod !in setOf("GET", "POST")) { respond(exchange, 405, mapOf("error" to "Method not supported")); return }
            if (exchange.requestMethod == "POST") {
                val origin = exchange.requestHeaders.getFirst("Origin")
                if ((origin != null && origin !in setOf(address, "http://localhost:${server.address.port}")) ||
                    exchange.requestHeaders.getFirst("X-Capital-Lab") != "local-demo" ||
                    exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';') != "application/json") {
                    respond(exchange, 403, mapOf("error" to "Use the local dashboard or a same-origin JSON request with X-Capital-Lab: local-demo")); return
                }
            }
            val path = exchange.requestURI.path
            when {
                path == "/api/catalog" && exchange.requestMethod == "GET" -> respond(exchange, 200, scenarios)
                path == "/api/dataset" && exchange.requestMethod == "GET" -> respond(exchange, 200, PublicDataset.json)
                path == "/api/runs" && exchange.requestMethod == "GET" -> respond(exchange, 200, runs.values.map { mapOf("id" to it.id, "title" to it.scenario.title) }.sortedBy { it["id"] })
                path == "/api/runs" && exchange.requestMethod == "POST" -> {
                    val input = body(exchange)
                    val scenarioId = input.requiredString("scenario")
                    val scenario = scenarios.singleOrNull { it.id == scenarioId } ?: throw IllegalArgumentException("Unknown scenario")
                    val date = if (scenario.id == "public-data") input.requiredString("datasetDate").also { PublicDataset.day(it) } else null
                    val run = createRun(scenario, date)
                    val snapshot = run.snapshot(); save(run.id, snapshot); respond(exchange, 201, snapshot)
                }
                path.matches(Regex("/api/runs/[a-f0-9]{32}(/step)?")) -> {
                    val id = path.split('/')[3]
                    val run = runs[id]
                    if (run == null) respond(exchange, 404, mapOf("error" to "Run is not in this server session. Saved evidence remains in build/dashboard-runs."))
                    else if (path.endsWith("/step") && exchange.requestMethod == "POST") {
                        val input = body(exchange)
                        val step = input["expectedStep"]
                        require(step != null && step.isJsonPrimitive && step.asJsonPrimitive.isNumber) { "expectedStep must be an integer" }
                        val snapshot = run.step(step.asBigDecimal.intValueExact()); save(id, snapshot); respond(exchange, 200, snapshot)
                    } else if (!path.endsWith("/step") && exchange.requestMethod == "GET") respond(exchange, 200, run.snapshot())
                    else respond(exchange, 405, mapOf("error" to "Method not supported"))
                }
                path in setOf("/", "/app.js", "/style.css") && exchange.requestMethod == "GET" -> {
                    val resource = if (path == "/") "/index.html" else path
                    val content = checkNotNull(javaClass.getResource("/dashboard$resource")).readBytes()
                    raw(exchange, 200, when (resource) { "/app.js" -> "text/javascript"; "/style.css" -> "text/css"; else -> "text/html" }, content)
                }
                else -> respond(exchange, 404, mapOf("error" to "Not found"))
            }
        } catch (failure: IllegalArgumentException) { respond(exchange, 400, mapOf("error" to (failure.message ?: "Invalid request"))) }
        catch (_: ArithmeticException) { respond(exchange, 400, mapOf("error" to "Expected an integer in range")) }
        catch (_: com.google.gson.JsonParseException) { respond(exchange, 400, mapOf("error" to "Invalid JSON")) }
        catch (failure: Exception) {
            System.err.println("Dashboard request failed: ${failure.javaClass.simpleName}: ${failure.message}")
            runCatching { respond(exchange, 500, mapOf("error" to "Operation failed. Check the server log and database availability; no automatic retry was performed.")) }
        } finally { exchange.close() }
    }
    @Synchronized private fun createRun(scenario: Scenario, date: String?): LabRun {
        require(runs.size < 100) { "This local session is limited to 100 runs. Restart the server for another session; evidence is preserved." }
        val id = UUID.randomUUID().toString().replace("-", "")
        val capital = Database(jdbcUrl, dbUser, dbPassword, "lab_c_$id")
        val bank = Database(jdbcUrl, dbUser, dbPassword, "lab_b_$id")
        capital.install("/db/capital.sql"); bank.install("/db/bank.sql")
        return LabRun(id, scenario, capital, bank, date).also { runs[id] = it }
    }
    private fun save(id: String, json: JsonObject) {
        evidenceDirectory?.let { directory ->
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "$id-", ".tmp")
            try { Files.writeString(temporary, gson.toJson(json)); Files.move(temporary, directory.resolve("$id.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
            finally { Files.deleteIfExists(temporary) }
        }
    }
    private fun body(exchange: HttpExchange): JsonObject {
        val bytes = exchange.requestBody.use { it.readNBytes(8193) }
        require(bytes.size <= 8192) { "Request too large" }
        val parsed = JsonParser.parseString(String(bytes, Charsets.UTF_8))
        require(parsed.isJsonObject) { "Expected a JSON object" }
        return parsed.asJsonObject
    }
    private fun JsonObject.requiredString(key: String): String {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key must be a string" }
        return value.asString
    }
    private fun respond(exchange: HttpExchange, status: Int, value: Any) = raw(exchange, status, "application/json", gson.toJson(value).toByteArray())
    private fun raw(exchange: HttpExchange, status: Int, type: String, bytes: ByteArray) {
        exchange.responseHeaders.set("Content-Type", "$type; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
    }
    override fun close() { server.stop(0); executor.shutdownNow() }
}

fun main() {
    val server = DashboardServer(
        System.getenv("LAB_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:55432/capital_lab",
        System.getenv("LAB_DB_USER") ?: "capital_lab",
        System.getenv("LAB_DB_PASSWORD") ?: "local_demo_only",
        System.getenv("LAB_HTTP_PORT")?.toInt() ?: 8080,
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    println("Capital Lab dashboard: ${server.address}")
    println("Synthetic money only. Run orchestration is session-local; database schemas and JSON evidence persist.")
}
