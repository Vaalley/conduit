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

    override fun bucket(uuid: UUID, world: String): PerWorldBucket? =
        read(uuid)[WORLDS]
            ?.let { PortalJson.parse(it.rawValue) }
            ?.get(world)
            ?.let(::decodeBucket)

    override fun setBucket(uuid: UUID, world: String, bucket: PerWorldBucket) {
        val record = read(uuid)
        // Only this World's slice is re-encoded; the other Worlds' buckets
        // (unknown keys from future versions included) pass through verbatim.
        val worlds = record[WORLDS]?.let { PortalJson.parse(it.rawValue) } ?: LinkedHashMap()
        worlds[world] = PortalJson.Field(PortalJson.encodeString(world), encodeBucket(bucket))
        write(uuid, WORLDS, PortalJson.emit(worlds.values))
    }

    /**
     * The bucket a raw `worlds.<world>` object slice denotes. Bucket fields are
     * mod-owned, so a slice missing one is corrupt data and throws (matching
     * the never-overwrite-what-we-cannot-read stance); unknown keys — fields a
     * newer version added — are ignored here and preserved by [setBucket].
     */
    private fun decodeBucket(field: PortalJson.Field): PerWorldBucket {
        val values = PortalJson.parse(field.rawValue)
        fun number(key: String): Double {
            val raw = requireNotNull(values[key]) { "bucket is missing \"$key\"" }.rawValue
            return raw.toDoubleOrNull()
                ?: throw IllegalArgumentException("bucket \"$key\" is not a number: $raw")
        }
        return PerWorldBucket(
            dimension = PortalJson.decodeString(
                requireNotNull(values[DIMENSION]) { "bucket is missing \"$DIMENSION\"" }.rawValue,
            ),
            x = number("x"),
            y = number("y"),
            z = number("z"),
            yaw = number("yaw").toFloat(),
            pitch = number("pitch").toFloat(),
        )
    }

    private fun encodeBucket(bucket: PerWorldBucket): String =
        """{"$DIMENSION":${PortalJson.encodeString(bucket.dimension)},""" +
            """"x":${bucket.x},"y":${bucket.y},"z":${bucket.z},""" +
            """"yaw":${bucket.yaw},"pitch":${bucket.pitch}}"""

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

        // Mod-owned fields (never written by the Portal): the Per-World
        // Buckets, one object per World id under one "worlds" object.
        const val WORLDS = "worlds"
        const val DIMENSION = "dimension"
    }
}
