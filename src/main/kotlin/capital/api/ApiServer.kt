package capital.api

import capital.automation.Cadence
import capital.automation.PayoutMode
import capital.automation.PayoutPolicy
import capital.automation.PayoutScheduler
import capital.config.HttpSettings
import capital.payments.AdvanceRequest
import capital.payments.AdvanceService
import capital.payments.Database
import capital.payments.FundingDeclined
import capital.payments.IdempotencyConflict
import capital.payments.rows
import capital.portfolio.PortfolioView
import capital.settlement.AdvanceReturnService
import capital.settlement.ProceedsRevision
import capital.settlement.ProceedsRevisionService
import capital.settlement.Remittance
import capital.settlement.RemittanceLine
import capital.settlement.RemittanceService
import capital.settlement.ResidualPayoutService
import capital.settlement.RevisionReason
import capital.underwriting.UnderwritingService
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonIOException
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private class ApiError(val status: Int, val code: String, override val message: String) :
    RuntimeException(message)

/**
 * Versioned tenant-facing API over the lifecycle the lab implements.
 *
 * Two boundaries are enforced here rather than left to callers:
 * - Tenancy. Every route resolves its developer from the API key, never from the request body, so a
 *   caller cannot name another developer's pool.
 * - Provenance. Facts that originate outside the developer — a store remitting money, a refund
 *   revising proceeds, a bank returning a transfer — require OPERATOR scope. A developer asserting
 *   "the store paid me" would otherwise be able to clear their own advance.
 *
 * This binds to loopback and is a local demonstration surface. It has no rate limiting, key
 * rotation, request signing, or replay window.
 */
class ApiServer(
    private val database: Database,
    private val http: HttpSettings = HttpSettings(port = 8082),
    private val clock: Clock,
    expectedReportThrough: LocalDate,
) : AutoCloseable {
    // java.time has no default Gson binding; without these, a date-bearing result fails to
    // serialize at all. ISO-8601 keeps the wire format unambiguous.
    private val gson: Gson =
        GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .registerTypeAdapter(
                LocalDate::class.java,
                JsonSerializer<LocalDate> { value, _, _ -> JsonPrimitive(value.toString()) },
            )
            .registerTypeAdapter(
                Instant::class.java,
                JsonSerializer<Instant> { value, _, _ -> JsonPrimitive(value.toString()) },
            )
            .create()

    private val keys = ApiKeys(database, clock)
    private val advances = AdvanceService(database, clock, expectedReportThrough)
    private val scheduler = PayoutScheduler(database, advances, clock)
    private val portfolio = PortfolioView(database)
    private val underwriting = UnderwritingService(database, clock)
    private val remittances = RemittanceService(database, clock)
    private val revisions = ProceedsRevisionService(database, clock)
    private val returns = AdvanceReturnService(database, clock)
    private val residuals = ResidualPayoutService(database, clock)

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

    fun issueKey(developerId: String, label: String, scope: Scope) =
        keys.issue(developerId, label, scope)

    init {
        server.executor = executor
        server.createContext("/", ::handle)
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        val requestId = UUID.randomUUID().toString()
        exchange.responseHeaders.set("X-Request-Id", requestId)
        try {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            // The spec and liveness are readable without a key so a client can bootstrap.
            if (path == "/v1/openapi.json" && method == "GET")
                return raw(exchange, 200, openApiDocument(), "application/json")
            if (path == "/v1/health" && method == "GET")
                return respond(exchange, 200, mapOf("status" to "UP"))

            val principal = keys.authenticate(exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, route(exchange, path, method, principal))
        } catch (failure: AuthenticationFailed) {
            exchange.responseHeaders.set("WWW-Authenticate", "Bearer")
            error(
                exchange,
                401,
                "UNAUTHENTICATED",
                failure.message ?: "Authentication failed",
                requestId,
            )
        } catch (failure: ApiError) {
            error(exchange, failure.status, failure.code, failure.message, requestId)
        } catch (failure: IdempotencyConflict) {
            error(exchange, 409, "IDEMPOTENCY_CONFLICT", failure.message!!, requestId)
        } catch (failure: FundingDeclined) {
            error(exchange, 422, "FUNDING_DECLINED", failure.message!!, requestId)
        } catch (_: JsonIOException) {
            error(
                exchange,
                500,
                "RESPONSE_NOT_SERIALIZABLE",
                "The result could not be encoded; this is a server defect, not a bad request",
                requestId,
            )
        } catch (_: JsonParseException) {
            error(exchange, 400, "INVALID_JSON", "Request body is not valid JSON", requestId)
        } catch (_: ArithmeticException) {
            error(
                exchange,
                400,
                "INVALID_INTEGER",
                "Amounts must be whole cents within range",
                requestId,
            )
        } catch (failure: IllegalArgumentException) {
            error(exchange, 400, "INVALID_REQUEST", failure.message ?: "Invalid request", requestId)
        } catch (failure: IllegalStateException) {
            error(exchange, 409, "CONFLICT", failure.message ?: "Conflicting state", requestId)
        } catch (_: SQLException) {
            error(exchange, 503, "DATABASE_UNAVAILABLE", "The database was unreachable", requestId)
        } catch (_: Exception) {
            error(
                exchange,
                500,
                "INTERNAL_ERROR",
                "Request failed; inspect logs by request id",
                requestId,
            )
        } finally {
            exchange.close()
        }
    }

    private fun route(
        exchange: HttpExchange,
        path: String,
        method: String,
        principal: Principal,
    ): Pair<Int, Any> {
        val segments = path.trim('/').split('/')
        if (segments.firstOrNull() != "v1")
            throw ApiError(404, "UNSUPPORTED_VERSION", "Only /v1 is served by this API")
        val rest = segments.drop(1)

        return when {
            rest.size == 3 &&
                rest[0] == "pools" &&
                rest[2] == "underwriting" &&
                method == "GET" -> {
                need(principal, Scope.READ)
                requireOwnedPool(principal, rest[1])
                val requested =
                    exchange.requestURI.query
                        ?.split('&')
                        ?.firstOrNull { it.startsWith("requestedCents=") }
                        ?.substringAfter('=')
                        ?.toLongOrNull() ?: 0L
                val assessment =
                    underwriting.assess(principal.developerId, rest[1], requested)
                        ?: throw ApiError(404, "NOT_FOUND", "No such pool for this developer")
                200 to
                    mapOf(
                        "poolId" to rest[1],
                        "policyVersion" to assessment.policyVersion,
                        "advisory" to
                            "Illustrative model output. Nothing here changes a limit or blocks a payout; " +
                                "the authoritative controls are enforced in the reservation path.",
                        "observation" to
                            mapOf(
                                "tenureDays" to assessment.observation.tenureDays,
                                "grossCents" to assessment.observation.grossCents,
                                "refundCents" to assessment.observation.refundCents,
                                "chargebackCents" to assessment.observation.chargebackCents,
                                "refundRateBasisPoints" to
                                    assessment.observation.refundRateBasisPoints,
                                "volatilityBasisPoints" to
                                    assessment.observation.volatilityBasisPoints,
                                "repaidPools" to assessment.observation.repaidPools,
                            ),
                        "advanceRate" to
                            mapOf(
                                "basisPoints" to assessment.rate.basisPoints,
                                "baseBasisPoints" to assessment.rate.baseBasisPoints,
                                "factors" to assessment.rate.factors,
                                "explanation" to assessment.rate.explanation,
                            ),
                        "exposure" to
                            mapOf(
                                "requestedCents" to assessment.exposure.requestedCents,
                                "permittedCents" to assessment.exposure.permittedCents,
                                "constraints" to assessment.exposure.constraints,
                                "explanation" to assessment.exposure.explanation,
                            ),
                        "fraud" to
                            mapOf(
                                "verdict" to assessment.fraud.verdict,
                                "signals" to assessment.fraud.signals,
                                "explanation" to assessment.fraud.explanation,
                            ),
                        "steppedLimitCents" to assessment.steppedLimitCents,
                    )
            }

            rest == listOf("portfolio") && method == "GET" -> {
                need(principal, Scope.READ)
                val view =
                    portfolio.of(principal.developerId)
                        ?: throw ApiError(404, "NOT_FOUND", "No portfolio for this developer")
                200 to
                    mapOf(
                        "developerId" to view.developerId,
                        "limitCents" to view.limitCents,
                        "outstandingCents" to view.outstandingCents,
                        "reservedCents" to view.reservedCents,
                        "headroomCents" to view.headroomCents,
                        "payableCents" to view.payableCents,
                        "openRecoveryCents" to view.openRecoveryCents,
                        "fundedLifetimeCents" to view.fundedLifetimeCents,
                        "onHold" to view.onHold,
                        "openPools" to view.openPools,
                        "largestPoolShareBasisPoints" to view.largestPoolShareBasisPoints,
                        "positions" to view.positions,
                    )
            }

            rest.size == 3 &&
                rest[0] == "pools" &&
                rest[2] == "availability" &&
                method == "GET" -> {
                need(principal, Scope.READ)
                requireOwnedPool(principal, rest[1])
                val availability = advances.available(principal.developerId, rest[1])
                200 to
                    mapOf(
                        "poolId" to rest[1],
                        "principalCents" to availability.principalCents,
                        "feeCents" to availability.feeCents,
                        "netCashCents" to availability.netCashCents,
                        "blockedReason" to availability.blockedReason,
                        "evidence" to JsonParser.parseString(availability.evidenceJson),
                    )
            }

            rest == listOf("advances") && method == "POST" -> {
                need(principal, Scope.WRITE)
                val body = body(exchange)
                val key =
                    exchange.requestHeaders.getFirst("Idempotency-Key")
                        ?: throw ApiError(
                            400,
                            "IDEMPOTENCY_KEY_REQUIRED",
                            "Send an Idempotency-Key header so a retry cannot fund twice",
                        )
                val poolId = body.string("poolId")
                requireOwnedPool(principal, poolId)
                val destination = database.transaction { connection ->
                    connection
                        .rows(
                            "SELECT destination_version FROM developers WHERE id=?",
                            principal.developerId,
                        ) {
                            it.getString(1)
                        }
                        .single()
                }
                val advance =
                    advances.reserve(
                        AdvanceRequest(
                            principal.developerId,
                            poolId,
                            body.cents("principalCents"),
                            destination,
                        ),
                        key,
                    )
                201 to advanceBody(advance)
            }

            rest.size == 2 && rest[0] == "advances" && method == "GET" -> {
                need(principal, Scope.READ)
                val advance =
                    try {
                        advances.get(UUID.fromString(rest[1]))
                    } catch (_: NoSuchElementException) {
                        throw ApiError(404, "NOT_FOUND", "No such advance")
                    }
                if (advance.developerId != principal.developerId)
                    throw ApiError(404, "NOT_FOUND", "No such advance")
                200 to advanceBody(advance)
            }

            rest.size == 3 && rest[0] == "advances" && rest[2] == "return" && method == "POST" -> {
                // A bank return is the provider's fact, not the developer's.
                need(principal, Scope.OPERATOR)
                val body = body(exchange)
                200 to returns.returnAdvance(UUID.fromString(rest[1]), body.string("reasonCode"))
            }

            rest == listOf("payout-policy") && method == "GET" -> {
                need(principal, Scope.READ)
                val policy =
                    scheduler.policy(principal.developerId)
                        ?: throw ApiError(404, "NOT_FOUND", "No payout policy is configured")
                200 to policy
            }

            rest == listOf("payout-policy") && method == "PUT" -> {
                need(principal, Scope.WRITE)
                val body = body(exchange)
                val policy =
                    PayoutPolicy(
                        principal.developerId,
                        enumOf<PayoutMode>(body.string("mode")),
                        body.cents("minimumCents"),
                        if (body.has("maximumCents")) body.cents("maximumCents") else 0,
                        if (body.has("cadence")) enumOf(body.string("cadence")) else Cadence.DAILY,
                        body.get("paused")?.asBoolean ?: false,
                    )
                scheduler.setPolicy(policy)
                200 to policy
            }

            rest == listOf("payout-cycles") && method == "POST" -> {
                need(principal, Scope.WRITE)
                200 to scheduler.runCycle(principal.developerId)
            }

            rest == listOf("residual-payouts") && method == "POST" -> {
                need(principal, Scope.WRITE)
                val body = body(exchange)
                200 to residuals.release(body.string("payoutId"), principal.developerId)
            }

            rest == listOf("remittances") && method == "POST" -> {
                // Only an operator may assert that a store sent money.
                need(principal, Scope.OPERATOR)
                val body = body(exchange)
                val lines =
                    body.getAsJsonArray("lines").map {
                        val line = it.asJsonObject
                        RemittanceLine(
                            line.string("poolId"),
                            line.cents("cents"),
                            line.get("final")?.asBoolean ?: false,
                        )
                    }
                200 to
                    remittances.apply(
                        Remittance(
                            body.string("remittanceId"),
                            body.string("store"),
                            body.string("developerId"),
                            body.cents("receivedCents"),
                            clock.instant(),
                            lines,
                        )
                    )
            }

            rest == listOf("proceeds-revisions") && method == "POST" -> {
                need(principal, Scope.OPERATOR)
                val body = body(exchange)
                200 to
                    revisions.revise(
                        ProceedsRevision(
                            body.string("revisionId"),
                            body.string("poolId"),
                            enumOf<RevisionReason>(body.string("reason")),
                            body.cents("reductionCents"),
                            clock.instant(),
                        )
                    )
            }

            else -> throw ApiError(404, "NOT_FOUND", "No route for $method $path")
        }
    }

    private fun advanceBody(advance: capital.payments.Advance) =
        mapOf(
            "id" to advance.id.toString(),
            "poolId" to advance.poolId,
            "principalCents" to advance.principalCents,
            "feeCents" to advance.feeCents,
            "cashCents" to advance.cashCents,
            "state" to advance.state,
            "bankTransferId" to advance.bankTransferId,
            "lastReason" to advance.lastReason,
        )

    private fun need(principal: Principal, required: Scope) {
        if (!principal.scope.allows(required))
            throw ApiError(
                403,
                "INSUFFICIENT_SCOPE",
                "This route needs $required scope; the key presented has ${principal.scope}",
            )
    }

    /** Tenancy is decided from the key, never from the path a caller chose. */
    private fun requireOwnedPool(principal: Principal, poolId: String) {
        val owned = database.transaction { connection ->
            connection
                .rows(
                    "SELECT 1 FROM pools WHERE id=? AND developer_id=?",
                    poolId,
                    principal.developerId,
                ) {
                    true
                }
                .isNotEmpty()
        }
        if (!owned) throw ApiError(404, "NOT_FOUND", "No pool $poolId for this developer")
    }

    private inline fun <reified T : Enum<T>> enumOf(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw ApiError(
                400,
                "INVALID_REQUEST",
                "Expected one of ${enumValues<T>().joinToString(", ") { it.name }}; got $value",
            )

    private fun body(exchange: HttpExchange): JsonObject {
        if (
            exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';') !=
                "application/json"
        )
            throw ApiError(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json")
        val bytes = exchange.requestBody.readNBytes(http.maxRequestBytes + 1)
        if (bytes.size > http.maxRequestBytes)
            throw ApiError(413, "REQUEST_TOO_LARGE", "Body exceeds ${http.maxRequestBytes} bytes")
        val parsed = JsonParser.parseString(String(bytes, Charsets.UTF_8))
        if (!parsed.isJsonObject) throw ApiError(400, "INVALID_JSON", "Body must be a JSON object")
        return parsed.asJsonObject
    }

    private fun respond(exchange: HttpExchange, result: Pair<Int, Any>) =
        raw(exchange, result.first, gson.toJson(result.second), "application/json")

    private fun respond(exchange: HttpExchange, status: Int, payload: Any) =
        raw(exchange, status, gson.toJson(payload), "application/json")

    private fun error(
        exchange: HttpExchange,
        status: Int,
        code: String,
        message: String,
        requestId: String,
    ) =
        raw(
            exchange,
            status,
            gson.toJson(
                mapOf(
                    "error" to mapOf("code" to code, "message" to message, "requestId" to requestId)
                )
            ),
            "application/json",
        )

    private fun raw(exchange: HttpExchange, status: Int, payload: String, contentType: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "$contentType; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(1)
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }
}

/** The served contract is the checked-in resource, so the two cannot drift apart. */
internal fun openApiDocument(): String =
    checkNotNull(ApiServer::class.java.getResource("/api/openapi.json")) {
            "The OpenAPI resource is missing from the build"
        }
        .readText()

private fun JsonObject.string(key: String): String {
    val value = get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        "$key must be a string"
    }
    return value.asString
}

private fun JsonObject.cents(key: String): Long {
    val value = get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
        "$key must be a whole number of cents"
    }
    return value.asBigDecimal.longValueExact()
}
