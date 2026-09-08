package eu.mctraveler.moderation

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

enum class ActionType { PLACE, BREAK, CONTAINER, BUCKET_FILL, BUCKET_EMPTY }

data class ActionEntry(
    val t: Long,
    val type: ActionType,
    val player: UUID,
    val playerName: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val block: String,
)

class ActionLog(private val file: Path) {
    private val gson = Gson()
    private val entries = mutableListOf<ActionEntry>()
    private val pending = mutableListOf<ActionEntry>()
    private val byPosition = mutableMapOf<Key, MutableList<ActionEntry>>()

    init {
        if (Files.exists(file)) {
            Files.readAllLines(file).forEach { line ->
                runCatching { gson.fromJson(line, ActionEntry::class.java) }.getOrNull()?.let(::addLoaded)
            }
            prune()
        }
    }

    fun record(
        type: ActionType,
        player: UUID,
        playerName: String,
        world: String,
        x: Int,
        y: Int,
        z: Int,
        block: String,
    ) {
        val entry = ActionEntry(System.currentTimeMillis(), type, player, playerName, world, x, y, z, block)
        entries += entry
        byPosition.getOrPut(Key(world, x, y, z), ::mutableListOf) += entry
        pending += entry
    }

    fun at(world: String, x: Int, y: Int, z: Int, since: Long = 0L): List<ActionEntry> =
        byPosition[Key(world, x, y, z)].orEmpty().filter { it.t >= since }.sortedByDescending { it.t }

    fun all(since: Long = 0L, type: ActionType? = null): List<ActionEntry> =
        entries.filter { it.t >= since && (type == null || it.type == type) }.sortedByDescending { it.t }

    fun flush() {
        if (pending.isEmpty()) return
        file.parent?.let(Files::createDirectories)
        Files.write(
            file,
            pending.joinToString("", transform = { gson.toJson(it) + "\n" }).toByteArray(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
        pending.clear()
    }

    private fun addLoaded(entry: ActionEntry) {
        entries += entry
        byPosition.getOrPut(Key(entry.world, entry.x, entry.y, entry.z), ::mutableListOf) += entry
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        val kept = entries.filter { it.t >= cutoff }
        if (kept.size == entries.size) return
        entries.clear()
        byPosition.clear()
        kept.forEach(::addLoaded)
        file.parent?.let(Files::createDirectories)
        Files.writeString(file, kept.joinToString("", transform = { gson.toJson(it) + "\n" }))
    }

    private data class Key(val world: String, val x: Int, val y: Int, val z: Int)
}
