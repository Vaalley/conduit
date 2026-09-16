package eu.mctraveler.economy

import com.google.gson.Gson
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
    private val gson = Gson()

    fun append(entry: LedgerEntry) {
        Files.createDirectories(directory)
        Files.writeString(
            fileFor(entry.player),
            gson.toJson(entry) + "\n",
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
                requireNotNull(gson.fromJson(line, LedgerEntry::class.java))
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
