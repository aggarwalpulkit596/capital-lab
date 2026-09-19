package capital.dashboard

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Single-server evidence repository. Archives are read-only observations, never payment
 * authorizations.
 */
class EvidenceStore(private val directory: Path?) {
    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val validId = Regex("[a-f0-9]{32}")

    /** Ignore late writers so a slow HTTP request cannot replace a newer checkpoint. */
    @Synchronized
    fun save(id: String, snapshot: JsonObject) {
        require(validId.matches(id) && snapshot["id"].asString == id)
        val root = directory ?: return
        val previous = read(id)
        if (previous != null && previous["nextStep"].asInt >= snapshot["nextStep"].asInt) return
        Files.createDirectories(root)
        val temporary = Files.createTempFile(root, "$id-", ".tmp")
        try {
            val document = snapshot.deepCopy().apply { addProperty("evidenceVersion", 1) }
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(gson.toJson(document).toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(
                temporary,
                root.resolve("$id.json"),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun read(id: String): JsonObject? {
        require(validId.matches(id))
        val file = directory?.resolve("$id.json") ?: return null
        if (!Files.isRegularFile(file)) return null
        // Evidence is generated locally, but bound memory when a file has been manually replaced.
        require(Files.size(file) <= 8 * 1024 * 1024) { "Evidence file exceeds the supported size" }
        val value = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
        require(value["id"].asString == id && value["nextStep"].asInt >= 0)
        return value
    }

    fun summaries(): List<Map<String, Any>> {
        val root = directory ?: return emptyList()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { files ->
            files
                .filter {
                    validId.matches(it.fileName.toString().removeSuffix(".json")) &&
                        it.fileName.toString().endsWith(".json")
                }
                .map { file ->
                    val id = file.fileName.toString().removeSuffix(".json")
                    val value = read(id)!!
                    mapOf<String, Any>(
                        "id" to id,
                        "title" to value["scenario"].asJsonObject["title"].asString,
                        "archived" to true,
                        "purpose" to (value["purpose"]?.asString ?: "scenario"),
                    )
                }
                .toList()
        }
    }
}
