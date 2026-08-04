package eu.mctraveler.importer

import eu.mctraveler.persistence.PortalJson
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Per-World Bucket: the player state each of the Portal's two Worlds kept
 * separately — where the player last stood, in which dimension of that World's
 * trio, and the respawn point their bed or anchor set there.
 *
 * **This is legacy data now.** The live server has no Worlds and restores no
 * buckets; nothing in the running mod reads or writes one. It lives in the
 * importer because the tools still meet it constantly and always will: `migrate`
 * writes buckets from the two backend saves, the merge sweep reads Secondary's
 * bucket and moves it, the End gate reads it to decide where to put down a
 * player stranded in a dimension it is about to delete, and the claim path banks
 * a returning player's other save into one years after the fact. All of those
 * run against records on disk, and records on disk still have the field.
 *
 * [dimension] is the trio-relative role — `"overworld"`, `"nether"` or `"end"`
 * (see [eu.mctraveler.worlds.DimensionRole]) — not a full dimension id, which is
 * how one schema served two Worlds.
 */
data class PerWorldBucket(
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    /** Where death sent the player in this World, or null — no bed or anchor was set here. */
    val respawn: RespawnPoint? = null,
)

/**
 * A respawn point as one World remembered it: the block a bed or respawn anchor
 * stood on, plus the facing to wake up with. Legacy data, for the same reason
 * [PerWorldBucket] is — the live server keeps vanilla's own respawn point now.
 *
 * [dimension] is the trio-relative role, as in [PerWorldBucket]. [forced] is
 * vanilla's "this point needs no block to back it" flag — what `/spawnpoint`
 * sets, as opposed to sleeping in a bed.
 */
data class RespawnPoint(
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val yaw: Float,
    val pitch: Float,
    val forced: Boolean,
)

/**
 * Reading and writing the Per-World Bucket field of a Portal-format player
 * record.
 *
 * This was `JsonPlayerStore`'s until the Worlds were retired. It moved rather
 * than being deleted because the field itself did not go anywhere — every
 * migrated record on the live server still carries a `worlds` object, and the
 * merge has to move what is inside it — but the *server* has no business
 * modelling it any more. On the store's side the field is now simply one more
 * legacy field, and gets the store's byte-for-byte pass-through guarantee for
 * free.
 *
 * Records are addressed by path rather than through a store, exactly as
 * [MergeStamp] addresses the stamp, because both are fields the live persistence
 * model does not own and both are written by tools that already know where the
 * file is.
 */
object PerWorldBuckets {

    /** The record field the buckets live in: one object per World id. */
    const val FIELD = "worlds"

    /**
     * The bucket [record] holds for [world] (`"primary"`/`"secondary"`), or null
     * if it holds none — which for a player who never left that World is the
     * ordinary state, and for a record that does not exist at all is the same
     * answer.
     */
    fun of(record: Path, world: String): PerWorldBucket? {
        if (Files.notExists(record)) return null
        return read(record)[FIELD]
            ?.let { PortalJson.parse(it.rawValue) }
            ?.get(world)
            ?.let(::decodeBucket)
    }

    /**
     * Writes [bucket] as [world]'s, leaving every other field's bytes exactly as
     * they were.
     *
     * Only this World's slice is re-encoded; the other World's bucket — and any
     * key inside it this version does not know — passes through verbatim.
     */
    fun into(record: Path, world: String, bucket: PerWorldBucket) {
        val fields = read(record)
        val worlds = fields[FIELD]?.let { PortalJson.parse(it.rawValue) } ?: LinkedHashMap()
        worlds[world] = PortalJson.Field(PortalJson.encodeString(world), encodeBucket(bucket))
        // Replacing keeps the field's position; a new field lands at the end.
        fields[FIELD] = PortalJson.Field(PortalJson.encodeString(FIELD), PortalJson.emit(worlds.values))
        Files.createDirectories(record.parent)
        Files.writeString(record, PortalJson.emit(fields.values))
    }

    private fun read(record: Path): LinkedHashMap<String, PortalJson.Field> {
        if (Files.notExists(record)) return LinkedHashMap()
        return PortalJson.parse(Files.readString(record))
    }

    /**
     * The bucket a raw `worlds.<world>` object slice denotes. Bucket fields were
     * mod-owned, so a slice missing one is corrupt data and throws (matching the
     * never-overwrite-what-we-cannot-read stance the store takes) — except
     * [RESPAWN], which is absent for a World the player set no bed in.
     */
    private fun decodeBucket(field: PortalJson.Field): PerWorldBucket {
        val values = PortalJson.parse(field.rawValue)
        return PerWorldBucket(
            dimension = string(values, DIMENSION, "bucket"),
            x = number(values, "x", "bucket"),
            y = number(values, "y", "bucket"),
            z = number(values, "z", "bucket"),
            yaw = number(values, "yaw", "bucket").toFloat(),
            pitch = number(values, "pitch", "bucket").toFloat(),
            respawn = values[RESPAWN]?.let(::decodeRespawn),
        )
    }

    private fun decodeRespawn(field: PortalJson.Field): RespawnPoint {
        val values = PortalJson.parse(field.rawValue)
        return RespawnPoint(
            dimension = string(values, DIMENSION, "respawn point"),
            x = number(values, "x", "respawn point").toInt(),
            y = number(values, "y", "respawn point").toInt(),
            z = number(values, "z", "respawn point").toInt(),
            yaw = number(values, "yaw", "respawn point").toFloat(),
            pitch = number(values, "pitch", "respawn point").toFloat(),
            forced = boolean(values, FORCED, "respawn point"),
        )
    }

    private fun encodeBucket(bucket: PerWorldBucket): String =
        """{"$DIMENSION":${PortalJson.encodeString(bucket.dimension)},""" +
            """"x":${bucket.x},"y":${bucket.y},"z":${bucket.z},""" +
            """"yaw":${bucket.yaw},"pitch":${bucket.pitch}""" +
            (bucket.respawn?.let { ""","$RESPAWN":${encodeRespawn(it)}""" } ?: "") +
            "}"

    private fun encodeRespawn(respawn: RespawnPoint): String =
        """{"$DIMENSION":${PortalJson.encodeString(respawn.dimension)},""" +
            """"x":${respawn.x},"y":${respawn.y},"z":${respawn.z},""" +
            """"yaw":${respawn.yaw},"pitch":${respawn.pitch},"$FORCED":${respawn.forced}}"""

    private fun string(values: Map<String, PortalJson.Field>, key: String, what: String): String =
        PortalJson.decodeString(raw(values, key, what))

    private fun number(values: Map<String, PortalJson.Field>, key: String, what: String): Double {
        val raw = raw(values, key, what)
        return raw.toDoubleOrNull()
            ?: throw IllegalArgumentException("$what \"$key\" is not a number: $raw")
    }

    private fun boolean(values: Map<String, PortalJson.Field>, key: String, what: String): Boolean =
        when (val raw = raw(values, key, what)) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("$what \"$key\" is not a boolean: $raw")
        }

    private fun raw(values: Map<String, PortalJson.Field>, key: String, what: String): String =
        requireNotNull(values[key]) { "$what is missing \"$key\"" }.rawValue

    private const val DIMENSION = "dimension"
    private const val RESPAWN = "respawn"
    private const val FORCED = "forced"
}
