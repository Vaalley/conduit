package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import kotlin.math.abs

/**
 * Secondary's world border, and the strip of ground beyond it the merge still
 * carries (merge spec, "What comes across"; ticket 13).
 *
 * Secondary has chunks nobody was ever meant to reach. An admin teleport, a stray
 * command, anything that put a player past the border for long enough to generate
 * terrain, leaves a chunk sitting a very long way from the rest of the map. None
 * of it is worth carrying and carrying it is actively harmful: the placement
 * search sizes Secondary's slot from the whole footprint, so one chunk a million
 * blocks out would demand an enormous free area in Primary and then drop a speck
 * of junk terrain into the middle of it.
 *
 * So the import is clipped, and two properties make the clip safe to add to a
 * merge that already works:
 *
 * - **It only ever shrinks.** What comes across is the intersection of "chunks
 *   that exist" with "inside the border plus the bleed", so a Secondary that
 *   never generated anything near its border is carried exactly as it would be
 *   without a clip at all.
 * - **It works in whole region files.** A file is carried only when the whole of
 *   it lies within [reach], so every chunk shares the fate of the file it lives
 *   in and a clipped footprint is still a rectangle of region files — which is
 *   what [MergeGeometry.OFFSET_ALIGNMENT] moves one-for-one, and what [Footprint]
 *   measures. The footprint the placement search sizes its slot from and the
 *   selection handed to the relocation are therefore answered by the very same
 *   predicate rather than by two that have to be kept in step.
 *
 * The rounding that second property implies is inward, and deliberately so: every
 * chunk carried is genuinely inside the border plus the bleed, and the price is
 * that the last part-file of bleed is not. With the default bleed of one whole
 * region file that price is bounded by what the bleed is for — the terrain stays
 * continuous past the border rather than ending at a visible wall — and erring
 * outward instead would mean carrying chunks from beyond a distance the operator
 * stated, which is the thing this type exists to stop.
 *
 * **[halfExtent] is not scaled by the nether's ÷8.** A vanilla world border
 * applies at the same coordinates in every dimension, so clipping the nether at
 * ±[halfExtent] *nether* blocks is the correct reading of the border Secondary
 * actually ran — and it is what catches a stray in the nether at all, since a
 * border divided by eight would throw away eight times as much real ground as it
 * should. Nothing here takes a [DimensionRole] for that reason.
 */
data class SecondaryBorder(
    /** Blocks from the origin to the border on each horizontal axis, as Secondary ran it. */
    val halfExtent: Int = WorldMerge.DEFAULT_BORDER,
    /**
     * Blocks of terrain carried past the border, so that a player standing at it
     * still sees ground beyond rather than the edge of what was imported.
     */
    val bleed: Int = WorldMerge.DEFAULT_BLEED,
) {

    init {
        require(halfExtent >= MergeGeometry.REGION_FILE_BLOCKS) {
            "Secondary's border must be at least ${MergeGeometry.REGION_FILE_BLOCKS} blocks from the " +
                "origin, because the clip carries whole region files and a smaller one would carry " +
                "none of them — got $halfExtent"
        }
        require(bleed >= 0) { "the bleed carried past the border cannot be negative, got $bleed" }
    }

    /** The furthest from the origin, on either axis, a carried block may lie. */
    val reach: Int get() = halfExtent + bleed

    /**
     * The region files the merge carries: every one lying wholly within [reach].
     *
     * A file at index `i` covers blocks `i × 512` through `i × 512 + 511`, so the
     * first carried file is the first whose near edge has not fallen short of
     * `−reach` and the last is the last whose far edge has not passed `+reach`.
     * The two are mirror images of each other because the interval they are cut
     * from is, which is what keeps a clipped footprint centred on the map
     * Secondary's players actually played on.
     */
    val files: RegionFileArea = RegionFileArea(
        minX = -(halfExtent + bleed).floorDiv(MergeGeometry.REGION_FILE_BLOCKS),
        minZ = -(halfExtent + bleed).floorDiv(MergeGeometry.REGION_FILE_BLOCKS),
        maxX = (halfExtent + bleed - MergeGeometry.REGION_FILE_BLOCKS + 1)
            .floorDiv(MergeGeometry.REGION_FILE_BLOCKS),
        maxZ = (halfExtent + bleed - MergeGeometry.REGION_FILE_BLOCKS + 1)
            .floorDiv(MergeGeometry.REGION_FILE_BLOCKS),
    )

    /** Whether [file] comes across at all. The clip's whole question, asked once. */
    fun keeps(file: RegionFilePos): Boolean = file in files

    /**
     * Whether a Secondary block coordinate is inside the border itself — the
     * bleed does not count here.
     *
     * This is the question asked *of the things the merge sweeps rather than
     * relocates*: a Region, a player or an Embassy destination anchored out
     * there. The border rather than the clip because the border is what the
     * operator declared and what they can check a count against, and because a
     * number that moved when the bleed did would be measuring a rendering nicety
     * rather than a boundary anyone was meant to cross.
     */
    fun contains(x: Int, z: Int): Boolean = abs(x) <= halfExtent && abs(z) <= halfExtent

    /** Whether a place a save records to the fraction of a block is inside the border; see [contains]. */
    fun contains(x: Double, z: Double): Boolean = abs(x) <= halfExtent && abs(z) <= halfExtent

    /**
     * How far past the border the furthest corner of [file] reaches, or 0 when
     * the file does not reach past it at all.
     *
     * Stated of the file rather than of a chunk inside it because the file is the
     * unit the clip works in, and because the number's job is to tell a stray
     * teleport from a real base — a difference of hundreds of thousands of
     * blocks, which the width of one region file cannot disguise.
     */
    fun blocksBeyond(file: RegionFilePos): Int =
        (maxOf(axisReach(file.x), axisReach(file.z)) - halfExtent).coerceAtLeast(0)

    /** The border and the bleed as the plan states them, so a rehearsal and the real run can be compared. */
    fun describe(): String = "±$halfExtent blocks, with $bleed of bleed carried past it"

    /** How far from the origin the far side of the region file at [index] lies. */
    private fun axisReach(index: Int): Int {
        val near = index * MergeGeometry.REGION_FILE_BLOCKS
        return maxOf(abs(near), abs(near + MergeGeometry.REGION_FILE_BLOCKS - 1))
    }
}

/**
 * What Secondary's border kept out, for the operator to check against what they
 * expected (merge spec, User Story 48).
 *
 * This section leads the ones the phases contribute because it is decided before
 * any of them run and constrains all of them: it is the statement of *what of
 * Secondary is coming across at all*, and the footprint the placement search
 * sized its slot from is the clipped one. It is reported by a plan as well as by
 * a merge, which is the point — an operator rehearsing sees the border, the bleed
 * and anything the clip would leave behind while there is still time to argue
 * with the numbers.
 *
 * The region files are named by dimension rather than counted, because "three
 * files, the furthest a million blocks out" is a stray teleport and "sixty files
 * just past the border" is somebody's base, and the operator is the only one who
 * can tell which.
 */
data class BorderClipReport(
    val border: SecondaryBorder,
    /** Secondary's region files the clip left behind, by the dimension each is in. */
    val leftOutside: Map<DimensionRole, List<RegionFilePos>>,
) : MergeSection {

    val filesLeftOutside: Int get() = leftOutside.values.sumOf { it.size }

    /** How far past the border the furthest file left behind reached, or null when none was. */
    val furthestBeyond: Int? get() = leftOutside.values.flatten().maxOfOrNull(border::blocksBeyond)

    override fun lines(): List<String> = listOf(
        reportLine("Secondary's border", border.describe()),
        reportLine(
            "left outside the border",
            furthestBeyond?.let {
                "$filesLeftOutside region file${if (filesLeftOutside == 1) "" else "s"}, " +
                    "the furthest reaching $it blocks past it"
            } ?: "nothing — every region file of Secondary is inside it",
        ),
    )
}
