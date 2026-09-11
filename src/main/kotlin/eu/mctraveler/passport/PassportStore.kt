package eu.mctraveler.passport

import com.google.gson.GsonBuilder
import eu.mctraveler.MCTraveler
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Gson-backed one-file-per-player passport store. The complete set is loaded
 * once so leaderboard reads never need to scan disk on the HTTP executor.
 */
class PassportStore(private val directory: Path) {
    private val gson = GsonBuilder().create()
    private val passports = LinkedHashMap<UUID, Passport>()
    private val dirty = LinkedHashSet<UUID>()

    init {
        if (Files.exists(directory)) {
            Files.list(directory).use { files ->
                files.filter { it.fileName.toString().endsWith(".json") }.forEach { file ->
                    try {
                        val uuid = UUID.fromString(file.fileName.toString().removeSuffix(".json"))
                        passports[uuid] = requireNotNull(gson.fromJson(Files.readString(file), Passport::class.java))
                    } catch (failure: Exception) {
                        MCTraveler.LOGGER.warn("Skipping corrupt passport file {}", file, failure)
                    }
                }
            }
        }
    }

    fun get(uuid: UUID): Passport? = passports[uuid]

    /**
     * The stored passport for [uuid], creating one stamped with
     * [firstJoinFallback] when none exists. The fallback is a supplier because
     * it reads a player record file — callers run on every tick and every
     * block break, and only the create path ever needs the value.
     */
    fun getOrCreate(uuid: UUID, firstJoinFallback: () -> Long): Passport =
        passports.getOrPut(uuid) { Passport(firstJoinFallback()).also { dirty.add(uuid) } }

    fun markDirty(uuid: UUID) {
        if (uuid in passports) dirty.add(uuid)
    }

    fun flushDirty() {
        if (dirty.isEmpty()) return
        Files.createDirectories(directory)
        val writing = dirty.toList()
        dirty.clear()
        writing.forEach { uuid ->
            val passport = passports[uuid] ?: return@forEach
            Files.writeString(directory.resolve("$uuid.json"), gson.toJson(passport))
        }
    }

    fun all(): Collection<Pair<UUID, Passport>> = passports.map { it.key to it.value }
}
