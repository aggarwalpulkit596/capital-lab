package capital

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.system.exitProcess

data class Fixture(
    val name: String,
    val snapshot: Snapshot,
    val policy: Policy,
    val context: EvaluationContext,
)

/** Parse explicit fixture fields; never silently substitute a missing amount or timestamp. */
fun loadFixtures(path: Path): List<Fixture> {
    val root = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
    require(root.string("currency") == "USD") {
        "This prototype only accepts USD; conversion is not implemented"
    }
    val context =
        EvaluationContext(
            Instant.parse(root.string("evaluatedAt")),
            LocalDate.parse(root.string("expectedReportThroughDate")),
            ZoneId.of(root.string("reportingZone")),
        )
    return root.getAsJsonArray("cases").map { element ->
        val item = element.asJsonObject
        Fixture(
            item.string("name"),
            Snapshot(
                item.string("poolId"),
                item.string("snapshotId"),
                LocalDate.parse(item.string("reportThroughDate")),
                Instant.parse(item.string("downloadedAt")),
                item.cents("netProceedsCents"),
                item.cents("existingExposureCents"),
            ),
            Policy(
                root.string("policyVersion"),
                item.cents("exposureCeilingCents"),
                allowedReportingLagDays =
                    root.cents("allowedReportingLagDays").let { Math.toIntExact(it) },
            ),
            context,
        )
    }
}

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
        "$key must be a number"
    }
    return value.asBigDecimal.longValueExact()
}

fun decisionRecord(name: String, decision: Decision): JsonObject =
    JsonObject().apply {
        addProperty("scenario", name)
        addProperty("recordType", "ILLUSTRATIVE_QUOTE_NOT_PAYMENT_AUTHORIZATION")
        addProperty("currency", "USD")
        add(
            "snapshot",
            JsonObject().apply {
                addProperty("poolId", decision.snapshot.poolId)
                addProperty("snapshotId", decision.snapshot.snapshotId)
                addProperty("reportThroughDate", decision.snapshot.reportThroughDate.toString())
                addProperty("downloadedAt", decision.snapshot.downloadedAt.toString())
                addProperty("netProceedsCents", decision.snapshot.netProceedsCents)
                addProperty("existingExposureCents", decision.snapshot.existingExposureCents)
            },
        )
        add(
            "policy",
            JsonObject().apply {
                addProperty("version", decision.policy.version)
                addProperty("exposureCeilingCents", decision.policy.exposureCeilingCents)
                addProperty("advanceBasisPoints", decision.policy.advanceBasisPoints)
                addProperty("feeBasisPoints", decision.policy.feeBasisPoints)
                addProperty("allowedReportingLagDays", decision.policy.allowedReportingLagDays)
                addProperty("advanceRounding", "FLOOR_TO_CENT")
                addProperty("feeRounding", "HALF_UP_TO_CENT")
                addProperty("feeCollection", "DEDUCT_FROM_QUOTED_PRINCIPAL")
            },
        )
        add(
            "context",
            JsonObject().apply {
                addProperty("evaluatedAt", decision.context.evaluatedAt.toString())
                addProperty(
                    "expectedReportThroughDate",
                    decision.context.expectedReportThroughDate.toString(),
                )
                addProperty("reportingZone", decision.context.reportingZone.id)
                addProperty(
                    "minimumReportThroughDate",
                    decision.minimumReportThroughDate.toString(),
                )
            },
        )
        addProperty("status", decision.status.name)
        add("reasons", JsonArray().apply { decision.reasons.forEach { add(it.name) } })
        addProperty("effectiveLimitCents", decision.effectiveLimitCents)
        addProperty("arithmeticCapacityCents", decision.arithmeticCapacityCents)
        addProperty("excessExposureCents", decision.excessExposureCents)
        addProperty("eligiblePrincipalCents", decision.eligiblePrincipalCents)
        addProperty("feeCents", decision.feeCents)
        addProperty("netCashCents", decision.netCashCents)
    }

const val DEFAULT_FIXTURE = "fixtures/report-coverage.json"

private val USAGE =
    """
    advance-eligibility-lab - print illustrative advance quotes for a fixture of synthetic pools.

    Usage:
      advance-eligibility-lab [FIXTURE]

    Arguments:
      FIXTURE   Path to a fixture JSON file. Defaults to $DEFAULT_FIXTURE,
                resolved against the current working directory.

    Options:
      -h, --help   Show this message and exit.

    Output is a JSON array of decision records on stdout; diagnostics go to stderr.
    Every record is an illustrative quote. It reserves no capacity and moves no money.
    """
        .trimIndent()

/** Renders one decision record per fixture case, or throws for unusable input. */
fun quoteFixtureFile(path: Path): String {
    val records = JsonArray()
    loadFixtures(path).forEach { fixture ->
        records.add(
            decisionRecord(
                fixture.name,
                evaluate(fixture.snapshot, fixture.policy, fixture.context),
            )
        )
    }
    return GsonBuilder().setPrettyPrinting().create().toJson(records)
}

/**
 * Translates an expected input failure into an operator-readable line. Programming errors keep
 * their stack trace so they stay visible as defects.
 */
private fun explain(path: Path, failure: Throwable): String =
    when (failure) {
        is NoSuchFileException ->
            "No fixture file at $path. Pass a path, or run from the repository root to use $DEFAULT_FIXTURE."
        is AccessDeniedException -> "Fixture file $path is not readable."
        is IOException -> "Could not read fixture file $path: ${failure.message ?: "I/O error"}"
        is JsonParseException ->
            "Fixture file $path is not valid JSON: ${failure.message ?: "parse error"}"
        is ArithmeticException ->
            "Fixture file $path has an amount that is not a whole number of cents: ${failure.message ?: ""}"
                .trim()
        is IllegalStateException -> "Fixture file $path is not a JSON object."
        is DateTimeParseException ->
            "Fixture file $path has an unparseable date or timestamp: ${failure.message}"
        is DateTimeException ->
            "Fixture file $path has an unusable date, time or zone: ${failure.message}"
        is IllegalArgumentException ->
            "Fixture file $path is not usable: ${failure.message ?: "invalid field"}"
        else -> throw failure
    }

fun main(args: Array<String>) {
    if (args.any { it == "-h" || it == "--help" }) {
        println(USAGE)
        return
    }
    if (args.size > 1) {
        System.err.println("Expected at most one fixture path, received ${args.size}.")
        System.err.println(USAGE)
        exitProcess(2)
    }
    val path = Path.of(args.firstOrNull() ?: DEFAULT_FIXTURE)
    val output =
        try {
            quoteFixtureFile(path)
        } catch (failure: Exception) {
            System.err.println(explain(path, failure))
            exitProcess(1)
        }
    println(output)
}
