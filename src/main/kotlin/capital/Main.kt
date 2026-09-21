package capital

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

fun main(args: Array<String>) {
    require(args.size <= 1) { "Usage: eligibility [fixture.json]" }
    val fixtures = loadFixtures(Path.of(args.firstOrNull() ?: "fixtures/report-coverage.json"))
    val records = JsonArray()
    fixtures.forEach { fixture ->
        records.add(
            decisionRecord(
                fixture.name,
                evaluate(fixture.snapshot, fixture.policy, fixture.context),
            )
        )
    }
    println(GsonBuilder().setPrettyPrinting().create().toJson(records))
}
