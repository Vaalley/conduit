package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType

/**
 * The arithmetic the merge of Secondary into Primary is built on, in one place
 * because everything downstream of the placement search has to agree with it:
 * the chunk relocation, the audit that proves it landed, the Regions and player
 * sweeps, and — for as long as a quarantined save can still be claimed — the
 * live claim path.
 *
 * Two rules do all the work, and both are load-bearing:
 *
 * - **The offset is a multiple of [OFFSET_ALIGNMENT] on each horizontal axis.**
 *   4096 is the smallest alignment for which *both* relocated dimensions move
 *   whole region files one-for-one, so no chunk is ever re-bucketed into a
 *   different file: 4096 blocks is 8 region files in the overworld, and its
 *   eighth, 512 blocks, is exactly one in the nether.
 * - **The nether moves one eighth as far** ([NETHER_DIVISOR]). That is the ratio
 *   vanilla links portals across, so a portal pair that lined up in Secondary
 *   still lines up once Secondary is part of Primary.
 *
 * Height is never touched by any of it, which is why nothing here has a Y at
 * all: a merge offset is a horizontal move and cannot express anything else.
 */
object MergeGeometry {

    /** Blocks. See the class note: the alignment that keeps region files whole in both dimensions. */
    const val OFFSET_ALIGNMENT = 4096

    /** The nether's ratio to the overworld — how far one nether block reaches, and so the offset's divisor. */
    const val NETHER_DIVISOR = 8

    /** Blocks along one edge of an `r.<x>.<z>.mca` file: 32 chunks. */
    const val REGION_FILE_BLOCKS = 512

    /**
     * The dimensions the merge actually moves. Secondary's End is discarded
     * rather than relocated (merge spec, "The End"), so it has no geometry —
     * every function here refuses it rather than quietly inventing an answer.
     */
    val RELOCATED_ROLES = listOf(DimensionRole.OVERWORLD, DimensionRole.NETHER)

    /**
     * How many overworld blocks one of [role]'s blocks stands for: 1 in the
     * overworld, [NETHER_DIVISOR] in the nether. Both the offset and the
     * clearance are stated in one dimension's blocks and divided down by this,
     * so the ÷8 relationship is written once.
     */
    fun overworldBlocksPer(role: DimensionRole): Int = when (role) {
        DimensionRole.OVERWORLD -> 1
        DimensionRole.NETHER -> NETHER_DIVISOR
        DimensionRole.END -> throw IllegalArgumentException(
            "Secondary's End is discarded by the merge rather than relocated, so it has no offset",
        )
    }

    /**
     * [netherBlocks] of clearance as [role] must measure it. The operator states
     * the clearance in *nether* blocks because the nether is the constraint that
     * actually binds — nether travel covers eight times the ground — so the
     * overworld has to be given eight times as much to be equally far away.
     */
    fun clearanceIn(role: DimensionRole, netherBlocks: Int): Int =
        netherBlocks * NETHER_DIVISOR / overworldBlocksPer(role)
}

/**
 * How far Secondary moves to become part of Primary: one horizontal vector,
 * stated as the overworld sees it.
 *
 * This is the merge's single coordinate authority. Nothing else may derive the
 * nether's move, and nothing else may decide what a Secondary coordinate
 * becomes — a value of this type is the only thing that knows, so the offline
 * merge and the claim path that outlives it by years cannot drift apart.
 *
 * The constructor enforces the alignment invariant, so an offset that exists at
 * all is one every source region file maps cleanly through.
 */
data class MergeOffset(val x: Int, val z: Int) {

    init {
        require(
            x % MergeGeometry.OFFSET_ALIGNMENT == 0 && z % MergeGeometry.OFFSET_ALIGNMENT == 0,
        ) {
            "a merge offset must be a multiple of ${MergeGeometry.OFFSET_ALIGNMENT} blocks on both " +
                "axes, so that every source region file lands on exactly one destination region " +
                "file — got x $x, z $z"
        }
    }

    /** Blocks [role]'s coordinates move on the X axis. */
    fun shiftX(role: DimensionRole): Int = x / MergeGeometry.overworldBlocksPer(role)

    /** Blocks [role]'s coordinates move on the Z axis. */
    fun shiftZ(role: DimensionRole): Int = z / MergeGeometry.overworldBlocksPer(role)

    /** The merged X a Secondary X becomes, in [role]. */
    fun mergedX(x: Int, role: DimensionRole): Int = x + shiftX(role)

    /** The merged Z a Secondary Z becomes, in [role]. */
    fun mergedZ(z: Int, role: DimensionRole): Int = z + shiftZ(role)

    /**
     * Region files [role] moves along X. Whole by construction — that is what
     * [MergeGeometry.OFFSET_ALIGNMENT] buys — so a source file's chunks all land
     * in one destination file.
     */
    fun regionFileShiftX(role: DimensionRole): Int = shiftX(role) / MergeGeometry.REGION_FILE_BLOCKS

    /** Region files [role] moves along Z. Whole by construction; see [regionFileShiftX]. */
    fun regionFileShiftZ(role: DimensionRole): Int = shiftZ(role) / MergeGeometry.REGION_FILE_BLOCKS

    /** The move as [role] sees it, for the report and for refusals: `x +8192, z +0`. */
    fun describe(role: DimensionRole): String = "x %+d, z %+d".format(shiftX(role), shiftZ(role))
}

/** One `r.<x>.<z>.mca` — a chunk file, wherever on disk it sits. */
data class RegionFilePos(val x: Int, val z: Int) {

    val fileName: String get() = "r.$x.$z.mca"

    companion object {
        /** The position [fileName] names, or null if it is not a region file name at all. */
        fun parse(fileName: String): RegionFilePos? {
            val parts = fileName.split('.')
            if (parts.size != 4 || parts[0] != "r" || parts[3] != "mca") return null
            val x = parts[1].toIntOrNull() ?: return null
            val z = parts[2].toIntOrNull() ?: return null
            return RegionFilePos(x, z)
        }
    }
}

/**
 * A rectangle of region files, inclusive at both ends.
 *
 * The merge reasons in whole region files rather than in blocks or chunks
 * because that is the granularity the placement search's question is asked at —
 * "does a file exist here?" — and because an offset moves whole files by
 * construction, so a rectangle of them stays a rectangle of them.
 */
data class RegionFileArea(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int) {

    val fileCount: Int get() = (maxX - minX + 1) * (maxZ - minZ + 1)

    val minBlockX: Int get() = minX * MergeGeometry.REGION_FILE_BLOCKS
    val minBlockZ: Int get() = minZ * MergeGeometry.REGION_FILE_BLOCKS
    val maxBlockX: Int get() = maxX * MergeGeometry.REGION_FILE_BLOCKS + MergeGeometry.REGION_FILE_BLOCKS - 1
    val maxBlockZ: Int get() = maxZ * MergeGeometry.REGION_FILE_BLOCKS + MergeGeometry.REGION_FILE_BLOCKS - 1

    operator fun contains(file: RegionFilePos): Boolean =
        file.x in minX..maxX && file.z in minZ..maxZ

    fun containsBlock(x: Int, z: Int): Boolean =
        x in minBlockX..maxBlockX && z in minBlockZ..maxBlockZ

    /** This area with [files] region files added on every side — the clearance ring. */
    fun grownBy(files: Int): RegionFileArea =
        RegionFileArea(minX - files, minZ - files, maxX + files, maxZ + files)

    /** Where this area lands once [offset] has moved [role]. */
    fun movedBy(offset: MergeOffset, role: DimensionRole): RegionFileArea {
        val shiftX = offset.regionFileShiftX(role)
        val shiftZ = offset.regionFileShiftZ(role)
        return RegionFileArea(minX + shiftX, minZ + shiftZ, maxX + shiftX, maxZ + shiftZ)
    }

    /**
     * How many region files separate [file] from this area, counting [file]
     * itself: 0 when it is inside, 1 when it is the very next one along.
     */
    fun filesTo(file: RegionFilePos): Int =
        maxOf(gap(minX, maxX, file.x), gap(minZ, maxZ, file.z))

    /** The block bounds, for the report: `x 0…1023  z 0…511`. */
    fun describeBlocks(): String = "x $minBlockX…$maxBlockX  z $minBlockZ…$maxBlockZ"

    private fun gap(min: Int, max: Int, at: Int): Int = maxOf(min - at, at - max, 0)
}

/**
 * Where one dimension's chunk data actually is, measured as the region files
 * present on disk.
 *
 * File existence is the whole measurement, deliberately: it is what the merge
 * spec defines a free slot against, it needs no chunk to be parsed, and it
 * cannot be fooled by a region file that has been emptied but not deleted —
 * which would still be a file the relocation had to land beside.
 */
class Footprint private constructor(
    /** Every region file here, deduplicated across the chunk folders and in a stable order. */
    val files: List<RegionFilePos>,
) {

    val isEmpty: Boolean get() = files.isEmpty()

    /** The rectangle the chunk data spans, or null when there is none. */
    val bounds: RegionFileArea? = if (files.isEmpty()) {
        null
    } else {
        RegionFileArea(files.minOf { it.x }, files.minOf { it.z }, files.maxOf { it.x }, files.maxOf { it.z })
    }

    /** The region files of this footprint that fall inside [area], at most [limit] of them. */
    fun within(area: RegionFileArea, limit: Int): List<RegionFilePos> =
        files.asSequence().filter { it in area }.take(limit).toList()

    /**
     * The empty space in blocks between [area] and the nearest chunk data here,
     * or null when there is none to be near. Negative would mean an overlap,
     * which every caller has already ruled out.
     */
    fun clearanceFrom(area: RegionFileArea): Int? = files.minOfOrNull { area.filesTo(it) }
        ?.let { (it - 1) * MergeGeometry.REGION_FILE_BLOCKS }

    companion object {
        /** The three folders a dimension's chunk data is split across. All three count. */
        val CHUNK_DIRECTORIES = listOf("region", "entities", "poi")

        /** Where [levelDir] keeps [dimension]'s chunk data. */
        fun storageFolder(levelDir: Path, dimension: ResourceKey<Level>): Path =
            DimensionType.getStorageFolder(dimension, levelDir)

        /** [dimension]'s footprint in [levelDir], across all of [CHUNK_DIRECTORIES]. */
        fun of(levelDir: Path, dimension: ResourceKey<Level>): Footprint {
            val storage = storageFolder(levelDir, dimension)
            val found = sortedSetOf<RegionFilePos>(compareBy({ it.x }, { it.z }))
            for (folder in CHUNK_DIRECTORIES) {
                val directory = storage.resolve(folder)
                if (!Files.isDirectory(directory)) continue
                Files.newDirectoryStream(directory, "r.*.mca").use { entries ->
                    entries.forEach { entry -> RegionFilePos.parse(entry.name)?.let(found::add) }
                }
            }
            return Footprint(found.toList())
        }
    }
}
