package capital

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class FixtureTest {
    @TempDir lateinit var temporaryDirectory: Path
    private val path = Path.of("fixtures/report-coverage.json")

    @Test fun `saved fixtures match hand worked expectations`() {
        val expected = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject.getAsJsonArray("cases") }
        val fixtures = loadFixtures(path)
        assertEquals(4, fixtures.size)
        fixtures.forEachIndexed { index, fixture ->
            val result = evaluate(fixture.snapshot, fixture.policy, fixture.context)
            val row = expected[index].asJsonObject
            assertEquals(row["expectedStatus"].asString, result.status.name, fixture.name)
            assertEquals(row["expectedArithmeticCapacityCents"].asLong, result.arithmeticCapacityCents, fixture.name)
            assertEquals(row["expectedPrincipalCents"].asLong, result.eligiblePrincipalCents, fixture.name)
            assertEquals(row["expectedFeeCents"].asLong, result.feeCents, fixture.name)
            assertEquals(row["expectedNetCashCents"].asLong, result.netCashCents, fixture.name)
            assertEquals(row["expectedExcessExposureCents"].asLong, result.excessExposureCents, fixture.name)
        }
    }

    @Test fun `decision JSON preserves inputs and distinguishes held diagnostic capacity`() {
        val fixture = loadFixtures(path)[1]
        val record = decisionRecord(fixture.name, evaluate(fixture.snapshot, fixture.policy, fixture.context))
        val json = JsonParser.parseString(record.toString()).asJsonObject
        assertEquals("HOLD", json["status"].asString)
        assertEquals(20_000, json["arithmeticCapacityCents"].asLong)
        assertEquals(0, json["eligiblePrincipalCents"].asLong)
        assertEquals("2026-09-17", json.getAsJsonObject("snapshot")["reportThroughDate"].asString)
        assertEquals("2026-09-19T18:00:00Z", json.getAsJsonObject("snapshot")["downloadedAt"].asString)
        assertEquals("illustrative-v1", json.getAsJsonObject("policy")["version"].asString)
        assertEquals("2026-09-18", json.getAsJsonObject("context")["minimumReportThroughDate"].asString)
    }

    @Test fun `fractional cents cannot be silently truncated at input`() {
        val input = Files.readString(path).replace("\"netProceedsCents\": 100000", "\"netProceedsCents\": 100000.5")
        val malformed = temporaryDirectory.resolve("fractional.json")
        Files.writeString(malformed, input)
        assertFailsWith<ArithmeticException> { loadFixtures(malformed) }
    }

    @Test fun `missing amount cannot default to zero`() {
        val input = Files.readString(path).replace("\"netProceedsCents\": 100000,", "")
        val malformed = temporaryDirectory.resolve("missing.json")
        Files.writeString(malformed, input)
        assertFailsWith<IllegalArgumentException> { loadFixtures(malformed) }
    }

    @Test fun `unsupported currency cannot be silently labelled USD`() {
        val input = Files.readString(path).replace("\"currency\": \"USD\"", "\"currency\": \"EUR\"")
        val malformed = temporaryDirectory.resolve("unsupported-currency.json")
        Files.writeString(malformed, input)
        assertFailsWith<IllegalArgumentException> { loadFixtures(malformed) }
    }
}
