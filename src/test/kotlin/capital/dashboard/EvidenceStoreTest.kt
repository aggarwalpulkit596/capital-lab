package capital.dashboard

import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EvidenceStoreTest {
    @TempDir lateinit var directory: Path
    private val id = "a".repeat(32)

    private fun snapshot(step: Int) =
        JsonObject().apply {
            addProperty("id", id)
            addProperty("nextStep", step)
            addProperty("purpose", "workspace")
            add("scenario", JsonObject().apply { addProperty("title", "Test scenario") })
        }

    @Test
    fun `late HTTP writer cannot replace newer evidence`() {
        val store = EvidenceStore(directory)
        store.save(id, snapshot(3))
        store.save(id, snapshot(2))
        assertEquals(3, EvidenceStore(directory).read(id)!!["nextStep"].asInt)
        assertEquals(1, store.summaries().size)
        assertEquals(true, store.summaries().single()["archived"])
    }

    @Test
    fun `concurrent evidence writers preserve the highest completed step`() {
        val store = EvidenceStore(directory)
        val executor = Executors.newFixedThreadPool(4)
        try {
            (20 downTo 0)
                .map { step -> executor.submit { store.save(id, snapshot(step)) } }
                .forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(20, store.read(id)!!["nextStep"].asInt)
        assertEquals(1, java.nio.file.Files.list(directory).use { it.count() })
    }

    @Test
    fun `evidence IDs cannot escape the configured directory`() {
        assertFailsWith<IllegalArgumentException> { EvidenceStore(directory).read("../private") }
        assertFailsWith<IllegalArgumentException> {
            EvidenceStore(directory)
                .save(id, snapshot(0).apply { addProperty("id", "b".repeat(32)) })
        }
    }

    @Test
    fun `telemetry records bounded route information and response counts`() {
        val messages = mutableListOf<String>()
        val telemetry = Telemetry(messages::add)
        telemetry.record("request-1", "GET", "/api/runs/{id}", 503, 25)
        telemetry.record("request-2", "GET", "/health/live", 200, 1)
        assertEquals(2L, telemetry.snapshot()["requestsCompleted"])
        assertEquals(1L, telemetry.snapshot()["serverErrors"])
        assertEquals(26L, telemetry.snapshot()["totalDurationMillis"])
        assertTrue(messages.all { "requestId" in it && "durationMillis" in it })
    }
}
