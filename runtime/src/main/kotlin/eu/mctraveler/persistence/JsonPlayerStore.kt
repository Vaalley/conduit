package eu.mctraveler.persistence

import java.nio.file.Files
import java.nio.file.Path
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
 * Writes are synchronous whole-file rewrites (as the Portal's were) and all
 * access is expected from the server thread.
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

    private fun readInt(uuid: UUID, key: String): Int? =
        read(uuid)[key]?.let { field ->
            field.rawValue.toIntOrNull()
                ?: throw IllegalArgumentException("\"$key\" is not a whole number: ${field.rawValue}")
        }

    /** The parsed player record, or an empty record for a player with no file. */
    private fun read(uuid: UUID): LinkedHashMap<String, PortalJson.Field> {
        val file = fileFor(uuid)
        if (Files.notExists(file)) return LinkedHashMap()
        return PortalJson.parse(Files.readString(file))
    }

    private fun write(uuid: UUID, key: String, rawValue: String) {
        val record = read(uuid)
        // Replacing keeps the field's position; a new field lands at the end.
        record[key] = PortalJson.Field(PortalJson.encodeString(key), rawValue)
        Files.createDirectories(playersDir)
        Files.writeString(fileFor(uuid), PortalJson.emit(record.values))
    }

    /** Drops [key] from the record; a no-op (no write at all) if it is absent. */
    private fun remove(uuid: UUID, key: String) {
        val record = read(uuid)
        if (record.remove(key) == null) return
        Files.createDirectories(playersDir)
        Files.writeString(fileFor(uuid), PortalJson.emit(record.values))
    }

    private fun fileFor(uuid: UUID): Path = playersDir.resolve("$uuid.json")

    private companion object {
        // The Portal's field names in players/<uuid>.json.
        const val LAST_WORLD = "lastServer"
        const val NOTEPAD = "notepad"

        // Teleportation Crystal energy, shared by all a player's crystals.
        const val CRYSTAL_ENERGY = "crystalEnergy"
        const val CRYSTAL_NEXT_REGEN_AT = "crystalNextRegenAt"
    }
}
