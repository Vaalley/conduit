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
 * `timestamps`, `ipAddress`, `isAdmin`, and anything else legacy data carries)
 * pass through byte-for-byte — each field's key and value slices verbatim.
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

    private fun fileFor(uuid: UUID): Path = playersDir.resolve("$uuid.json")

    private companion object {
        // The Portal's field names in players/<uuid>.json.
        const val LAST_WORLD = "lastServer"
        const val NOTEPAD = "notepad"
    }
}
