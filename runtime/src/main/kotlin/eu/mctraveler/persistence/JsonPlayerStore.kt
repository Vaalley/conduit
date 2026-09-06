package eu.mctraveler.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID

/**
 * [PlayerStore] over the Portal's flat-JSON player files: one
 * `<uuid>.json` per player under [playersDir], schema-compatible with
 * `players/<uuid>.json` as written by the Portal's PersistenceModule.
 *
 * Every mutation is a read-modify-write through [PortalJson], so fields this
 * mod doesn't own (`balance`, `geoLocation`, `balanceBeheadingLoss`,
 * `timestamps`, `ipAddress`, `isAdmin`, the `worlds` object the retired
 * Per-World Buckets live in, and anything else legacy data carries) pass
 * through byte-for-byte — each field's key and value slices verbatim.
 * The compact top-level layout (as the Portal wrote) is canonical: whitespace
 * *between* fields in a hand-edited file is not retained. A file that fails to
 * parse makes the operation throw rather than ever overwrite data we couldn't
 * read.
 *
 * Reads are served from an in-memory copy revalidated against the file's
 * size and mtime, so hand edits are still picked up; writes remain
 * synchronous whole-file rewrites (as the Portal's were). All access is
 * expected from the server thread.
 */
class JsonPlayerStore(private val playersDir: Path) : PlayerStore {

    override fun lastWorld(uuid: UUID): String? =
        read(uuid)[LAST_WORLD]?.let { PortalJson.decodeString(it.rawValue) }

    override fun setLastWorld(uuid: UUID, world: String) =
        write(uuid, LAST_WORLD, PortalJson.encodeString(world))

    override fun notepadPages(uuid: UUID): List<String>? =
        read(uuid)[NOTEPAD]?.let { pages ->
            PortalJson.parseStringArray(pages.rawValue)
        }

    override fun setNotepadPages(uuid: UUID, pages: List<String>) =
        write(uuid, NOTEPAD, pages.joinToString(",", "[", "]", transform = PortalJson::encodeString))

    override fun crystalEnergy(uuid: UUID): Int? = readInt(uuid, CRYSTAL_ENERGY)

    override fun setCrystalEnergy(uuid: UUID, energy: Int) =
        write(uuid, CRYSTAL_ENERGY, energy.toString())

    override fun crystalNextRegenAt(uuid: UUID): Int? = readInt(uuid, CRYSTAL_NEXT_REGEN_AT)

    override fun setCrystalNextRegenAt(uuid: UUID, playTimeTicks: Int?) {
        // No pending recharge is the *absence* of the field, not a sentinel:
        // a full player's record looks like one that never spent any energy.
        if (playTimeTicks == null) remove(uuid, CRYSTAL_NEXT_REGEN_AT)
        else write(uuid, CRYSTAL_NEXT_REGEN_AT, playTimeTicks.toString())
    }

    override fun setCrystalState(uuid: UUID, energy: Int, nextRegenAt: Int?) {
        val record = read(uuid)
        val energyChanged = record[CRYSTAL_ENERGY]?.rawValue != energy.toString()
        val pendingChanged = if (nextRegenAt == null) {
            CRYSTAL_NEXT_REGEN_AT in record
        } else {
            record[CRYSTAL_NEXT_REGEN_AT]?.rawValue != nextRegenAt.toString()
        }
        if (!energyChanged && !pendingChanged) return
        record[CRYSTAL_ENERGY] = PortalJson.Field(PortalJson.encodeString(CRYSTAL_ENERGY), energy.toString())
        // No pending recharge is the *absence* of the field, not a sentinel.
        if (nextRegenAt == null) record.remove(CRYSTAL_NEXT_REGEN_AT)
        else record[CRYSTAL_NEXT_REGEN_AT] =
            PortalJson.Field(PortalJson.encodeString(CRYSTAL_NEXT_REGEN_AT), nextRegenAt.toString())
        persist(uuid, record)
    }

    override fun hasRecord(uuid: UUID): Boolean = Files.exists(fileFor(uuid))

    override fun rank(uuid: UUID): String? = read(uuid)[RANK]?.let { PortalJson.decodeString(it.rawValue) }

    override fun setRank(uuid: UUID, rank: String) = write(uuid, RANK, PortalJson.encodeString(rank))

    private fun readInt(uuid: UUID, key: String): Int? =
        read(uuid)[key]?.let { field ->
            field.rawValue.toIntOrNull()
                ?: throw IllegalArgumentException("\"$key\" is not a whole number: ${field.rawValue}")
        }

    /**
     * The parsed player record, or an empty record for a player with no file.
     * The returned map is the caller's working copy: [write], [remove] and
     * [setCrystalState] mutate it before [persist], and unchanged files are
     * answered by the cached copy rather than re-read and re-parsed.
     */
    private fun read(uuid: UUID): LinkedHashMap<String, PortalJson.Field> {
        val file = fileFor(uuid)
        if (Files.notExists(file)) {
            cache.remove(uuid)
            return LinkedHashMap()
        }
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
        cache[uuid]?.let {
            if (it.size == attrs.size() && it.modified == attrs.lastModifiedTime()) return it.record
        }
        val record = PortalJson.parse(Files.readString(file))
        cache[uuid] = Cached(attrs.size(), attrs.lastModifiedTime(), record)
        return record
    }

    private fun write(uuid: UUID, key: String, rawValue: String) {
        val record = read(uuid)
        // Replacing keeps the field's position; a new field lands at the end.
        record[key] = PortalJson.Field(PortalJson.encodeString(key), rawValue)
        persist(uuid, record)
    }

    /** Drops [key] from the record; a no-op (no write at all) if it is absent. */
    private fun remove(uuid: UUID, key: String) {
        val record = read(uuid)
        if (record.remove(key) == null) return
        persist(uuid, record)
    }

    /**
     * Rewrites [uuid]'s file from [record] and caches it under the new stat. On
     * any failure the cache entry is dropped: [record] may already carry a
     * mutation that never reached disk, so serving it afterwards would lie.
     */
    private fun persist(uuid: UUID, record: LinkedHashMap<String, PortalJson.Field>) {
        try {
            Files.createDirectories(playersDir)
            val file = fileFor(uuid)
            Files.writeString(file, PortalJson.emit(record.values))
            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
            cache[uuid] = Cached(attrs.size(), attrs.lastModifiedTime(), record)
        } catch (e: Exception) {
            cache.remove(uuid)
            throw e
        }
    }

    /** A parsed record and the file stat it was read under. */
    private class Cached(
        val size: Long,
        val modified: FileTime,
        val record: LinkedHashMap<String, PortalJson.Field>,
    )

    private val cache = HashMap<UUID, Cached>()

    private fun fileFor(uuid: UUID): Path = playersDir.resolve("$uuid.json")

    private companion object {
        // The Portal's field names in players/<uuid>.json.
        const val LAST_WORLD = "lastServer"
        const val NOTEPAD = "notepad"

        // Teleportation Crystal energy, shared by all a player's crystals.
        const val CRYSTAL_ENERGY = "crystalEnergy"
        const val CRYSTAL_NEXT_REGEN_AT = "crystalNextRegenAt"

        // Ranks: Newbie/Traveler/Donator.
        const val RANK = "rank"
    }
}
