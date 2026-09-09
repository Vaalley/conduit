package eu.mctraveler.dragonfight

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Synchronously persists completed players and the short-lived arena records.
 */
class DragonFightState(private val file: Path) {
    data class ArenaRecord(val createdAt: Long, val guests: Set<UUID>)

    private val completed = LinkedHashSet<UUID>()
    private val arenaRecords = LinkedHashMap<UUID, ArenaRecord>()

    init {
        load()
    }

    fun isCompleted(uuid: UUID): Boolean = uuid in completed

    fun markCompleted(uuid: UUID) {
        if (completed.add(uuid)) save()
    }

    fun forgetCompleted(uuid: UUID) {
        if (completed.remove(uuid)) save()
    }

    fun arena(owner: UUID): ArenaRecord? = arenaRecords[owner]

    fun putArena(owner: UUID, record: ArenaRecord) {
        arenaRecords[owner] = record
        save()
    }

    fun removeArena(owner: UUID) {
        if (arenaRecords.remove(owner) != null) save()
    }

    fun clearArenas() {
        if (arenaRecords.isNotEmpty()) {
            arenaRecords.clear()
            save()
        }
    }

    fun arenas(): Map<UUID, ArenaRecord> = arenaRecords.toMap()

    private fun load() {
        if (Files.notExists(file)) return
        runCatching {
            val root = JsonParser.parseString(Files.readString(file)).asJsonObject
            root.getAsJsonArray("completed")?.forEach { completed += UUID.fromString(it.asString) }
            root.getAsJsonObject("arenas")?.entrySet()?.forEach { (owner, value) ->
                val record = value.asJsonObject
                val guests = record.getAsJsonArray("guests")?.map { UUID.fromString(it.asString) }.orEmpty().toSet()
                arenaRecords[UUID.fromString(owner)] = ArenaRecord(record.get("createdAt").asLong, guests)
            }
        }.getOrElse {
            completed.clear()
            arenaRecords.clear()
        }
    }

    private fun save() {
        file.parent?.let(Files::createDirectories)
        val root = JsonObject()
        root.add("completed", JsonArray().apply { completed.forEach { add(it.toString()) } })
        root.add("arenas", JsonObject().apply {
            arenaRecords.forEach { (owner, record) ->
                add(owner.toString(), JsonObject().apply {
                    addProperty("createdAt", record.createdAt)
                    add("guests", JsonArray().apply { record.guests.forEach { add(it.toString()) } })
                })
            }
        })
        Files.writeString(file, GsonBuilder().setPrettyPrinting().create().toJson(root))
    }
}
