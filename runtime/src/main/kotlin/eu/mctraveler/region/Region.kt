package eu.mctraveler.region

import com.google.gson.JsonElement
import java.util.UUID

/**
 * A player-owned protected cuboid in a World's dimension (see `CONTEXT.md`),
 * with members, flags, and optional sub-regions — the Portal's Region model.
 *
 * Corners are stored as the two points the creator captured, un-normalised
 * (start/end may be swapped on any axis, as in legacy data); [contains] and
 * [intersectsColumn] normalise. Y bounds keep the legacy convention: `startY`
 * is the top (default 320), `endY` the bottom (default −64) — the defaults new
 * regions get (full build height, deviation 2) and the values omitted in the
 * stored form.
 *
 * [world] is the Portal's legacy world string (`world`, `world_nether`,
 * `world_the_end`, `last`, `last_nether`, `last_the_end` — see [RegionWorlds]),
 * kept for storage compatibility with migrated data.
 */
class Region(
    var title: String,
    val world: String,
    var startX: Int,
    var startZ: Int,
    var endX: Int,
    var endZ: Int,
    var startY: Int = DEFAULT_START_Y,
    var endY: Int = DEFAULT_END_Y,
) {
    val members: LinkedHashSet<UUID> = LinkedHashSet()
    val flags: LinkedHashSet<String> = LinkedHashSet()
    val subRegions: MutableList<Region> = mutableListOf()

    /**
     * Free-form JSON a feature hangs off a region, stored under an optional
     * `"metadata"` object (deviation 6) and written only when non-empty, so
     * every region that has none is byte-identical to before it existed.
     *
     * The one key in use is `embassy-destination` (an object of `x`, `y`, `z`,
     * `yaw`, `pitch` and a legacy `world` string) — where an embassy's anchor
     * sends the player who stands on it.
     *
     * Insertion-ordered, because the file it round-trips through is.
     */
    val metadata: LinkedHashMap<String, JsonElement> = LinkedHashMap()

    /** The region this one nests inside, or null for a root region. */
    var parent: Region? = null
        internal set

    val minX: Int get() = minOf(startX, endX)
    val maxX: Int get() = maxOf(startX, endX)
    val minZ: Int get() = minOf(startZ, endZ)
    val maxZ: Int get() = maxOf(startZ, endZ)
    val minY: Int get() = minOf(startY, endY)
    val maxY: Int get() = maxOf(startY, endY)

    /** Whether the block position is inside this region (all axes inclusive). */
    fun contains(x: Int, y: Int, z: Int): Boolean =
        x in minX..maxX && z in minZ..maxZ && y in minY..maxY

    /**
     * Whether this region's x/z footprint intersects the given column at all —
     * the full-intersection overlap test (deviation 3; the Portal only looked
     * for corners falling inside the new rectangle).
     */
    fun intersectsColumn(minX: Int, maxX: Int, minZ: Int, maxZ: Int): Boolean =
        this.minX <= maxX && this.maxX >= minX && this.minZ <= maxZ && this.maxZ >= minZ

    fun isResident(uuid: UUID): Boolean = uuid in members

    /** This region and every region it nests inside, innermost first. */
    fun selfAndAncestors(): Sequence<Region> = generateSequence(this) { it.parent }

    companion object {
        /** Default top y — full build height, also the omit-on-save value. */
        const val DEFAULT_START_Y = 320

        /** Default bottom y — full build depth, also the omit-on-save value. */
        const val DEFAULT_END_Y = -64

        /**
         * The one flag that makes a region an embassy.
         *
         * It lives here rather than in the embassy module because the region
         * layer is underneath it: `/rg` refuses to toggle this flag and refuses
         * to delete or nest inside a region carrying it, all without knowing
         * what an embassy *is*. Spelling the same literal in both modules is
         * two places for a typo to hide, and a typo here reads as "not an
         * embassy" rather than as an error.
         */
        const val EMBASSY_FLAG = "EMBASSY"
    }
}
