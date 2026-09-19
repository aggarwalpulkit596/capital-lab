package capital.payments

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

data class BankCommand(val providerKey: String, val amountCents: Long, val currency: String, val destination: String)
sealed interface BankResult {
    data class Settled(val transferId: String) : BankResult
    data object Rejected : BankResult
    data object NotFound : BankResult
    data class Unknown(val reason: String) : BankResult
}

interface BankGateway {
    fun submit(command: BankCommand): BankResult
    fun lookup(command: BankCommand): BankResult
}

internal fun fingerprint(vararg fields: Any): String {
    val encoded = Gson().toJson(fields.toList()).toByteArray(StandardCharsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
}

internal fun BankCommand.hash(): String = fingerprint(providerKey, amountCents, currency, destination)

/** Only targets the local simulator. A real bank adapter requires its own authentication and contract. */
class HttpBankGateway(private val endpoint: URI) : BankGateway {
    init {
        require(endpoint.scheme == "http" && endpoint.host == "127.0.0.1") { "Simulator endpoint must be loopback HTTP" }
    }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    override fun submit(command: BankCommand): BankResult {
        val body = Gson().toJson(command)
        val request = HttpRequest.newBuilder(endpoint.resolve("/transfers"))
            .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return send(request, command, allowNotFound = false)
    }

    override fun lookup(command: BankCommand): BankResult {
        require(command.providerKey.matches(Regex("[a-zA-Z0-9_-]+")))
        val request = HttpRequest.newBuilder(endpoint.resolve("/transfers/${command.providerKey}"))
            .timeout(Duration.ofSeconds(3)).GET().build()
        return send(request, command, allowNotFound = true)
    }

    private fun send(request: HttpRequest, command: BankCommand, allowNotFound: Boolean): BankResult = try {
        // A request/connect timeout alone did not bound a partially delivered response body in testing.
        // Bound the future that includes body consumption, then cancel the transport on any failure.
        val pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        val response = try {
            pending.get(3, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            pending.cancel(true)
            throw failure
        }
        if (allowNotFound && response.statusCode() == 404) BankResult.NotFound
        else if (response.statusCode() != 200) BankResult.Unknown("HTTP_${response.statusCode()}")
        else {
            val json = JsonParser.parseString(response.body()).asJsonObject
            require(json.requiredString("providerKey") == command.providerKey)
            require(json["amountCents"].asBigDecimal.longValueExact() == command.amountCents)
            require(json.requiredString("currency") == command.currency)
            require(json.requiredString("destination") == command.destination)
            when (json.requiredString("state")) {
                "SETTLED" -> BankResult.Settled(json.requiredString("transferId").also { require(it.isNotBlank()) })
                "REJECTED" -> BankResult.Rejected
                else -> BankResult.Unknown("UNRECOGNIZED_BANK_STATE")
            }
        }
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        BankResult.Unknown("INTERRUPTED")
    } catch (_: Exception) {
        // Transport failures and unverifiable evidence both preserve the original reservation.
        BankResult.Unknown("TRANSPORT_OR_INVALID_RESPONSE")
    }
}

internal fun JsonObject.requiredString(name: String): String {
    val field = get(name)
    require(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString)
    return field.asString
}
