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
 * synchronous whole-file rewrites (as the Portal's were). Revalidation is
 * itself throttled — every read paying a `stat` pair still costs a syscall
 * storm when the question is asked per tick and per packet, so within
 * [REVALIDATE_INTERVAL_MS] of the last check the cached copy (including a
 * remembered "no file") is trusted outright; hand edits surface within a
 * second. The window does not apply to the read half of a mutation — a
 * read-modify-write must build on the file as it now stands, since other
 * tools (the merge sweep's Per-World Buckets edit being the live example)
 * write these files without going through the store. All access is expected
 * from the server thread.
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
        val record = readForWrite(uuid)
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

    override fun firstJoin(uuid: UUID): Long? {
        val timestamps = try {
            read(uuid)["timestamps"]?.rawValue ?: return null
        } catch (_: Exception) {
            return null
        }
        return try {
            PortalJson.parse(timestamps)["firstSeen"]?.rawValue?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    override fun lastLoginAt(uuid: UUID): Long? = readLong(uuid, LAST_LOGIN_AT)

    override fun lastLogoutAt(uuid: UUID): Long? = readLong(uuid, LAST_LOGOUT_AT)

    override fun lastLocationWorld(uuid: UUID): String? =
        read(uuid)[LAST_LOCATION_WORLD]?.let { PortalJson.decodeString(it.rawValue) }

    override fun lastX(uuid: UUID): Double? = readDouble(uuid, LAST_X)

    override fun lastY(uuid: UUID): Double? = readDouble(uuid, LAST_Y)

    override fun lastZ(uuid: UUID): Double? = readDouble(uuid, LAST_Z)

    override fun lastIp(uuid: UUID): String? =
        read(uuid)[LAST_IP]?.let { PortalJson.decodeString(it.rawValue) }
            ?: read(uuid)["ipAddress"]?.let { PortalJson.decodeString(it.rawValue) }

    override fun setLoginMetadata(uuid: UUID, at: Long, ip: String?) {
        val record = readForWrite(uuid)
        record[LAST_LOGIN_AT] = PortalJson.Field(PortalJson.encodeString(LAST_LOGIN_AT), at.toString())
        if (ip == null) record.remove(LAST_IP)
        else record[LAST_IP] = PortalJson.Field(PortalJson.encodeString(LAST_IP), PortalJson.encodeString(ip))
        persist(uuid, record)
    }

    override fun setLogoutMetadata(uuid: UUID, at: Long, world: String, x: Double, y: Double, z: Double) {
        val record = readForWrite(uuid)
        record[LAST_LOGOUT_AT] = PortalJson.Field(PortalJson.encodeString(LAST_LOGOUT_AT), at.toString())
        record[LAST_LOCATION_WORLD] = PortalJson.Field(PortalJson.encodeString(LAST_LOCATION_WORLD), PortalJson.encodeString(world))
        record[LAST_X] = PortalJson.Field(PortalJson.encodeString(LAST_X), x.toString())
        record[LAST_Y] = PortalJson.Field(PortalJson.encodeString(LAST_Y), y.toString())
        record[LAST_Z] = PortalJson.Field(PortalJson.encodeString(LAST_Z), z.toString())
        persist(uuid, record)
    }

    private fun readInt(uuid: UUID, key: String): Int? =
        read(uuid)[key]?.let { field ->
            field.rawValue.toIntOrNull()
                ?: throw IllegalArgumentException("\"$key\" is not a whole number: ${field.rawValue}")
        }

    private fun readLong(uuid: UUID, key: String): Long? =
        read(uuid)[key]?.rawValue?.toLongOrNull()
            ?: read(uuid)[key]?.let { throw IllegalArgumentException("\"$key\" is not a whole number: ${it.rawValue}") }

    private fun readDouble(uuid: UUID, key: String): Double? =
        read(uuid)[key]?.rawValue?.toDoubleOrNull()
            ?: read(uuid)[key]?.let { throw IllegalArgumentException("\"$key\" is not a number: ${it.rawValue}") }

    /**
     * The parsed player record, or an empty record for a player with no file.
     * The returned map is the caller's working copy: [write], [remove] and
     * [setCrystalState] mutate it before [persist], and unchanged files are
     * answered by the cached copy rather than re-read and re-parsed.
     */
    private fun read(uuid: UUID): LinkedHashMap<String, PortalJson.Field> = read(uuid, trustWindow = true)

    /**
     * [read] without the revalidation window: always re-stats the file. Every
     * mutator's read-modify-write goes through it — a windowed cache hit there
     * could silently drop a write another tool made since our last look.
     */
    private fun readForWrite(uuid: UUID): LinkedHashMap<String, PortalJson.Field> =
        read(uuid, trustWindow = false)

    private fun read(uuid: UUID, trustWindow: Boolean): LinkedHashMap<String, PortalJson.Field> {
        val now = System.currentTimeMillis()
        if (trustWindow) cache[uuid]?.let {
            if (now < it.nextCheckMillis) return it.record
        }
        val file = fileFor(uuid)
        if (Files.notExists(file)) {
            cache[uuid] = Cached(fileExists = false, size = -1, modified = null, LinkedHashMap(), now + REVALIDATE_INTERVAL_MS)
            return cache.getValue(uuid).record
        }
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
        cache[uuid]?.let {
            if (it.fileExists && it.size == attrs.size() && it.modified == attrs.lastModifiedTime()) {
                it.nextCheckMillis = now + REVALIDATE_INTERVAL_MS
                return it.record
            }
        }
        val record = PortalJson.parse(Files.readString(file))
        cache[uuid] = Cached(fileExists = true, attrs.size(), attrs.lastModifiedTime(), record, now + REVALIDATE_INTERVAL_MS)
        return record
    }

    private fun write(uuid: UUID, key: String, rawValue: String) {
        val record = readForWrite(uuid)
        // Replacing keeps the field's position; a new field lands at the end.
        record[key] = PortalJson.Field(PortalJson.encodeString(key), rawValue)
        persist(uuid, record)
    }

    /** Drops [key] from the record; a no-op (no write at all) if it is absent. */
    private fun remove(uuid: UUID, key: String) {
        val record = readForWrite(uuid)
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
            cache[uuid] = Cached(
                fileExists = true,
                attrs.size(),
                attrs.lastModifiedTime(),
                record,
                System.currentTimeMillis() + REVALIDATE_INTERVAL_MS,
            )
        } catch (e: Exception) {
            cache.remove(uuid)
            throw e
        }
    }

    /**
     * A parsed record, the file stat it was read under, and when to next
     * re-check the file. [fileExists] false entries remember an absent file so
     * repeated reads of a never-written player do not stat on every call.
     */
    private class Cached(
        val fileExists: Boolean,
        val size: Long,
        val modified: FileTime?,
        val record: LinkedHashMap<String, PortalJson.Field>,
        var nextCheckMillis: Long,
    )

    private val cache = HashMap<UUID, Cached>()

    private fun fileFor(uuid: UUID): Path = playersDir.resolve("$uuid.json")

    private companion object {
        /**
         * How long a cached answer (present or absent) is trusted before the
         * file is re-stat'ed. The mod is the only writer in normal play —
         * [persist] refreshes the cache directly — so this only delays notice
         * of a hand edit.
         */
        const val REVALIDATE_INTERVAL_MS = 1000L

        // The Portal's field names in players/<uuid>.json.
        const val LAST_WORLD = "lastServer"
        const val NOTEPAD = "notepad"

        // Teleportation Crystal energy, shared by all a player's crystals.
        const val CRYSTAL_ENERGY = "crystalEnergy"
        const val CRYSTAL_NEXT_REGEN_AT = "crystalNextRegenAt"

        // Ranks: Newbie/Traveler/Donator.
        const val RANK = "rank"
        const val LAST_LOGIN_AT = "lastLoginAt"
        const val LAST_LOGOUT_AT = "lastLogoutAt"
        const val LAST_LOCATION_WORLD = "lastWorld"
        const val LAST_X = "lastX"
        const val LAST_Y = "lastY"
        const val LAST_Z = "lastZ"
        const val LAST_IP = "lastIp"
    }
}
