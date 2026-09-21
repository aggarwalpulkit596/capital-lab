package capital

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/**
 * The packaged executable is the first thing a reader runs. These cases pin the contract it
 * publishes: a JSON array on stdout for usable input, and a single explanatory line for input the
 * evaluator must refuse.
 */
class CommandLineTest {
    @TempDir lateinit var temporaryDirectory: Path

    private val defaultFixture = Path.of(DEFAULT_FIXTURE)

    private fun corrupt(name: String, transform: (String) -> String): Path {
        val malformed = temporaryDirectory.resolve(name)
        Files.writeString(malformed, transform(Files.readString(defaultFixture)))
        return malformed
    }

    @Test
    fun `documented default fixture path exists and renders one record per case`() {
        val records = JsonParser.parseString(quoteFixtureFile(defaultFixture)).asJsonArray
        assertEquals(loadFixtures(defaultFixture).size, records.size())
        records.forEach {
            assertEquals(
                "ILLUSTRATIVE_QUOTE_NOT_PAYMENT_AUTHORIZATION",
                it.asJsonObject["recordType"].asString,
            )
        }
    }

    @Test
    fun `a missing fixture is reported as input, not as a crash`() {
        val absent = temporaryDirectory.resolve("absent.json")
        val message =
            assertFailsWith<java.nio.file.NoSuchFileException> { quoteFixtureFile(absent) }.file
        assertEquals(absent.toString(), message)
    }

    @Test
    fun `refusals name the offending field rather than a stack position`() {
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                    quoteFixtureFile(
                        corrupt("no-proceeds.json") {
                            it.replace("\"netProceedsCents\": 100000,", "")
                        }
                    )
                }
                .message!!
                .contains("netProceedsCents")
        )
    }

    @Test
    fun `stdout stays parseable JSON so the output can be piped`() {
        val output = quoteFixtureFile(defaultFixture)
        assertTrue(output.startsWith("["), "output must begin the JSON array with no banner line")
        assertTrue(JsonParser.parseString(output).isJsonArray)
    }
}
