package capital.payments

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class BankFault { NONE, LOSE_RESPONSE_AFTER_COMMIT, LOSE_RESPONSE_BEFORE_EXECUTION, REJECT_BEFORE_PAYMENT }

/** Real loopback HTTP, independently committed PostgreSQL data, synthetic money only. */
class FakeBankServer(private val database: Database) : AutoCloseable {
    val nextFault = AtomicReference(BankFault.NONE)
    val postRequests = AtomicInteger()
    val lookupRequests = AtomicInteger()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
        it.executor = executor
        it.createContext("/transfers", ::handle)
        it.start()
    }
    val endpoint: URI get() = URI("http://127.0.0.1:${server.address.port}")

    private fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod) {
                "GET" -> {
                    lookupRequests.incrementAndGet()
                    val key = exchange.requestURI.path.removePrefix("/transfers/")
                    val record = database.transaction { connection ->
                        connection.rows("SELECT * FROM bank_operations WHERE provider_key = ?", key, read = ::asJson).singleOrNull()
                    }
                    if (record == null) respond(exchange, 404, "{}") else respond(exchange, 200, record.toString())
                }
                "POST" -> {
                    postRequests.incrementAndGet()
                    val body = exchange.requestBody.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
                    val command = BankCommand(body.requiredString("providerKey"), body["amountCents"].asBigDecimal.longValueExact(), body.requiredString("currency"), body.requiredString("destination"))
                    require(command.amountCents > 0 && command.currency == "USD" && command.destination.isNotBlank())
                    val fault = nextFault.getAndSet(BankFault.NONE)
                    if (fault == BankFault.LOSE_RESPONSE_BEFORE_EXECUTION) {
                        truncated(exchange, "{}")
                        return
                    }
                    val record = database.transaction { connection ->
                        val balance = connection.number("SELECT cents FROM bank_balance WHERE id = 1 FOR UPDATE")
                        val existing = connection.rows("SELECT * FROM bank_operations WHERE provider_key = ?", command.providerKey) {
                            it.getString("payload_hash") to asJson(it)
                        }.singleOrNull()
                        if (existing != null) {
                            require(existing.first == command.hash()) { "Idempotency payload conflict" }
                            connection.update("UPDATE bank_operations SET submit_count = submit_count + 1 WHERE provider_key = ?", command.providerKey)
                            existing.second
                        } else {
                            val state = if (fault == BankFault.REJECT_BEFORE_PAYMENT || balance < command.amountCents) "REJECTED" else "SETTLED"
                            connection.update("""INSERT INTO bank_operations(provider_key,payload_hash,transfer_id,currency,amount_cents,destination,state)
                                VALUES (?,?,?,?,?,?,?)""", command.providerKey, command.hash(), "bank_${UUID.randomUUID()}", command.currency, command.amountCents, command.destination, state)
                            if (state == "SETTLED") connection.update("UPDATE bank_balance SET cents = cents - ? WHERE id = 1", command.amountCents)
                            connection.rows("SELECT * FROM bank_operations WHERE provider_key = ?", command.providerKey, read = ::asJson).single()
                        }
                    }
                    // The independent bank transaction has COMMITTED before this transport fault.
                    if (fault == BankFault.LOSE_RESPONSE_AFTER_COMMIT) {
                        truncated(exchange, record.toString())
                    } else respond(exchange, 200, record.toString())
                }
                else -> respond(exchange, 405, "{}")
            }
        } catch (_: IllegalArgumentException) {
            runCatching { respond(exchange, 409, "{}") }
        } catch (_: Exception) {
            runCatching { respond(exchange, 500, "{}") }
        } finally { exchange.close() }
    }

    private fun asJson(row: ResultSet): JsonObject = JsonObject().apply {
        addProperty("providerKey", row.getString("provider_key"))
        addProperty("transferId", row.getString("transfer_id"))
        addProperty("amountCents", row.getLong("amount_cents"))
        addProperty("currency", row.getString("currency"))
        addProperty("destination", row.getString("destination"))
        addProperty("state", row.getString("state"))
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() { server.stop(0); executor.shutdownNow() }

    private fun truncated(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, (bytes.size + 100).toLong())
        exchange.responseBody.write(bytes, 0, bytes.size / 2)
        runCatching { exchange.responseBody.close() }
        exchange.close()
    }
}
