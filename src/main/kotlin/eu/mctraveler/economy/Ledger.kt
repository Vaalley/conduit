package eu.mctraveler.economy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import eu.mctraveler.MCTraveler
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

data class LedgerEntry(
    val at: Long,
    val player: UUID,
    val delta: Long,
    val balance: Long,
    val reason: String,
)

class Ledger(private val directory: Path) {
    fun append(entry: LedgerEntry) {
        Files.createDirectories(directory)
        val json = JsonObject().apply {
            addProperty("at", entry.at)
            addProperty("player", entry.player.toString())
            add("delta", JsonPrimitive(Economy.toDecimal(entry.delta)))
            add("balance", JsonPrimitive(Economy.toDecimal(entry.balance)))
            addProperty("reason", entry.reason)
        }
        Files.writeString(
            fileFor(entry.player),
            json.toString() + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    fun read(uuid: UUID): List<LedgerEntry> {
        val file = fileFor(uuid)
        if (Files.notExists(file)) return emptyList()
        return Files.readAllLines(file).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            try {
                val json = JsonParser.parseString(line).asJsonObject
                LedgerEntry(
                    at = json.get("at").asLong,
                    player = UUID.fromString(json.get("player").asString),
                    delta = Economy.fromDecimal(json.get("delta").asBigDecimal),
                    balance = Economy.fromDecimal(json.get("balance").asBigDecimal),
                    reason = json.get("reason").asString,
                )
            } catch (failure: Exception) {
                MCTraveler.LOGGER.warn("Skipping unparsable ledger entry in {}", file, failure)
                null
            }
        }
    }

    fun all(): Sequence<LedgerEntry> {
        if (Files.notExists(directory)) return emptySequence()
        val files = Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }.toList()
        }
        return files.asSequence().flatMap { file ->
            val uuid = runCatching {
                UUID.fromString(file.fileName.toString().removeSuffix(".jsonl"))
            }.getOrElse {
                MCTraveler.LOGGER.warn("Skipping ledger file with invalid player uuid {}", file, it)
                return@flatMap emptySequence()
            }
            read(uuid).asSequence()
        }
    }

    private fun fileFor(uuid: UUID): Path = directory.resolve("$uuid.jsonl")
}
