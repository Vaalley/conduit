package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.name
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo

/** What the respawn cross-check looked at, for the operator to read as evidence. */
data class RespawnCheckReport(
    /** Respawn points that were moved and whose bed was found where the move put it. */
    val confirmed: Int,
    /**
     * Respawn points that were moved but had no bed behind them *before* the
     * merge either. Their owners already respawned at the world spawn; the merge
     * has not taken anything from them, and says so rather than staying quiet.
     */
    val alreadyWithoutABed: Int,
) : MergeSection {
    override fun lines(): List<String> = listOf(
        reportLine(
            "respawn points moved",
            "${confirmed + alreadyWithoutABed} — $confirmed confirmed against the relocated chunks" +
                if (alreadyWithoutABed == 0) {
                    ""
                } else {
                    ", $alreadyWithoutABed had no bed before the merge either"
                },
        ),
    )
}

/**
 * The cross-check the audit cannot make on its own: every respawn point the
 * player sweep moved still has the bed it names standing under it (merge spec,
 * "Audit"; ticket 07).
 *
 * A respawn point and the bed it names are moved by two different passes of the
 * merge — the point by the player sweep, the bed by the chunk relocation — and
 * nothing else in the merge would notice if they disagreed. Each pass is
 * self-consistent either way, so proving the arithmetic against itself proves
 * nothing; the only evidence is going to look, in the relocated chunk data, for
 * a bed at the coordinates the swept save now claims.
 *
 * **What it will not do is invent a problem that was already there.** A respawn
 * point whose bed was broken months ago is completely ordinary on a live server
 * — vanilla keeps the point and tells its owner the bed was missing when they
 * die — and failing a merge over hundreds of those would make the merge
 * unrunnable while proving nothing about the relocation. So the question asked
 * is the one the two passes can actually disagree about: *where Secondary's own
 * chunk data has a bed or a respawn anchor at the point's old position, the
 * relocated chunk data must have one at its new position.* Points with no bed
 * behind them either way are counted and reported, not refused.
 *
 * Only a player's own respawn point is checked. A Per-World Bucket carries one
 * too, but that is the bed of a World that stops existing at this merge and
 * nobody will ever respawn at it.
 *
 * Both sides are read straight out of region files rather than through a loaded
 * level, and Secondary's are only ever *read*: its chunk data comes out of the
 * merge byte for byte, which is what makes the pre-merge backup a rollback.
 */
class RespawnBeds(
    private val plan: MergePlan,
    private val staging: MergeStaging,
    /** Primary's dimensions as the relocation has just rebuilt them inside the staging area. */
    private val stagedLevelDir: Path,
) {

    private val levelDir: Path = plan.targetDir.resolve(plan.levelName)
    private val playerdataDir: Path = levelDir.resolve(PLAYERDATA_DIRECTORY)

    fun check(): RespawnCheckReport {
        var confirmed = 0
        var withoutABed = 0
        for ((live, uuid) in movedSaves()) {
            val before = respawnPointIn(NbtIo.readCompressed(live, NbtAccounter.unlimitedHeap()))
            val role = before?.let(::secondaryRole) ?: continue
            if (role !in MergeGeometry.RELOCATED_ROLES) continue
            if (!hasRespawnBlock(levelDir, WorldLayout.SECONDARY.dimension(role), before)) {
                withoutABed++
                continue
            }
            val after = respawnPointIn(NbtIo.readCompressed(staging.latest(live), NbtAccounter.unlimitedHeap()))
                ?: throw MigrationRefused(unmoved(uuid, before))
            if (!hasRespawnBlock(stagedLevelDir, WorldLayout.PRIMARY.dimension(role), after)) {
                throw MigrationRefused(lost(uuid, before, after))
            }
            confirmed++
        }
        return RespawnCheckReport(confirmed, withoutABed)
    }

    // ---- the refusals -------------------------------------------------------

    private fun lost(uuid: UUID, before: RespawnAt, after: RespawnAt): String =
        "player $uuid respawns at ${before.describe()}, where Secondary has a bed or a respawn " +
            "anchor, and the merge moved that respawn point to ${after.describe()} — but no bed and " +
            "no respawn anchor arrived there. The respawn point and the block it names are moved by " +
            "two different passes of the merge, and these two disagree, so the merge stopped rather " +
            "than wake somebody up inside solid rock. Nothing has been written"

    private fun unmoved(uuid: UUID, before: RespawnAt): String =
        "player $uuid respawns at ${before.describe()}, which the merge has to move, but their swept " +
            "save records no respawn point at all. Nothing has been written"

    // ---- what a save says ---------------------------------------------------

    /**
     * Saves the merge actually rewrote, paired with whose they are. A save it
     * never staged is one it had no change to make to, so no respawn point of
     * theirs was moved and there is nothing here to check.
     */
    private fun movedSaves(): List<Pair<Path, UUID>> {
        if (Files.notExists(playerdataDir)) return emptyList()
        return Files.list(playerdataDir).use { entries ->
            entries.toList().sortedBy(Path::toString).mapNotNull { file ->
                val suffix = SAVE_SUFFIXES.firstOrNull { file.name.endsWith(it) } ?: return@mapNotNull null
                if (staging.latest(file) == file) return@mapNotNull null
                runCatching { UUID.fromString(file.name.removeSuffix(suffix)) }.getOrNull()
                    ?.let { file to it }
            }
        }
    }

    /** A dimension and the block in it a player wakes up on. */
    private data class RespawnAt(val dimension: String, val x: Int, val y: Int, val z: Int) {
        fun describe(): String = "x $x, y $y, z $z in $dimension"
    }

    /**
     * Where [save] says its owner respawns, in either spelling: the `respawn`
     * global position 1.21.5 folded it into, or the flat `Spawn*` fields a
     * player who has not logged in since still has on disk (see
     * [MergedPlayerdata], which moves both).
     */
    private fun respawnPointIn(save: CompoundTag): RespawnAt? {
        val modern = save.getCompound(RESPAWN).orElse(null)
        if (modern != null) {
            val pos = modern.getIntArray(GLOBAL_POS).orElse(null)
            if (pos != null && pos.size == BLOCK_POS_LENGTH) {
                return RespawnAt(modern.getStringOr(GLOBAL_POS_DIMENSION, ""), pos[0], pos[1], pos[2])
            }
        }
        if (!save.contains(LEGACY_SPAWN_DIMENSION)) return null
        return RespawnAt(
            save.getStringOr(LEGACY_SPAWN_DIMENSION, ""),
            save.getIntOr(LEGACY_SPAWN_X, 0),
            save.getIntOr(LEGACY_SPAWN_Y, 0),
            save.getIntOr(LEGACY_SPAWN_Z, 0),
        )
    }

    private fun secondaryRole(at: RespawnAt): DimensionRole? =
        DimensionRole.entries.firstOrNull { WorldLayout.SECONDARY.dimensionId(it) == at.dimension }

    // ---- what a chunk says --------------------------------------------------

    /**
     * Whether [dimension]'s chunk data under [level] has a bed or a charged-or-not
     * respawn anchor standing at [at] — the same two blocks vanilla will look for
     * when its owner next dies.
     *
     * A chunk that is not there is an answer, not an error: a respawn point in a
     * region file the merge never carried across is exactly the disagreement
     * this check exists to catch.
     */
    private fun hasRespawnBlock(level: Path, dimension: ResourceKey<Level>, at: RespawnAt): Boolean =
        blockAt(level, dimension, at)?.let { it == RESPAWN_ANCHOR || it.endsWith(BED_SUFFIX) } ?: false

    /**
     * The name of the block at [at], read out of the region file it is stored
     * in. Null when nothing there can answer — no region file, no chunk, no
     * section, or a section stored against the global block palette rather than
     * one of its own, which only happens above 256 distinct blocks in sixteen
     * cubic metres and never where somebody put a bed.
     */
    private fun blockAt(level: Path, dimension: ResourceKey<Level>, at: RespawnAt): String? {
        val chunk = chunkAt(level, dimension, at) ?: return null
        val section = chunk.getListOrEmpty(SECTIONS).firstOrNull {
            it is CompoundTag && it.getByteOr(SECTION_Y, 0) == (at.y shr SECTION_SHIFT).toByte()
        } as? CompoundTag ?: return null
        val states = section.getCompound(BLOCK_STATES).orElse(null) ?: return null
        val palette = states.getListOrEmpty(PALETTE)
        if (palette.isEmpty()) return null
        // A section of one block state stores no indices at all, because there is
        // nothing to tell apart.
        val data = states.getLongArray(DATA).orElse(null)
        if (data == null || data.isEmpty()) return palette.entryName(0)
        val bits = maxOf(MINIMUM_BITS, PALETTE_INDEX_BITS - Integer.numberOfLeadingZeros(palette.size - 1))
        val perLong = Long.SIZE_BITS / bits
        val index = ((at.y and BLOCK_MASK) shl (AXIS_BITS * 2)) or
            ((at.z and BLOCK_MASK) shl AXIS_BITS) or (at.x and BLOCK_MASK)
        val word = data.getOrNull(index / perLong) ?: return null
        val entry = (word ushr (index % perLong * bits)).toInt() and ((1 shl bits) - 1)
        return palette.entryName(entry)
    }

    private fun ListTag.entryName(index: Int): String? =
        getCompound(index).orElse(null)?.getStringOr(BLOCK_NAME, "")?.takeIf(String::isNotEmpty)

    /**
     * The chunk holding [at], read straight out of its region file.
     *
     * The file is opened only when it is already there: [RegionFile] creates the
     * file it is pointed at, and creating one in the live save would leave
     * Secondary's chunk data changed by a check that is supposed only to look at
     * it.
     */
    private fun chunkAt(level: Path, dimension: ResourceKey<Level>, at: RespawnAt): CompoundTag? {
        val folder = Footprint.storageFolder(level, dimension).resolve(TERRAIN)
        val chunk = ChunkPos(at.x shr CHUNK_SHIFT, at.z shr CHUNK_SHIFT)
        val file = folder.resolve(RegionFilePos(chunk.x shr REGION_SHIFT, chunk.z shr REGION_SHIFT).fileName)
        if (Files.notExists(file)) return null
        return RegionFile(RegionStorageInfo(plan.levelName, dimension, TERRAIN), file, folder, false).use {
            if (!it.hasChunk(chunk)) null else it.getChunkDataInputStream(chunk)?.use(NbtIo::read)
        }
    }

    private companion object {
        const val PLAYERDATA_DIRECTORY = "playerdata"
        val SAVE_SUFFIXES = listOf(".dat", ".dat_old")

        /** The folder holding the blocks; a bed is terrain, not an entity or a point of interest. */
        const val TERRAIN = "region"

        const val RESPAWN = "respawn"
        const val GLOBAL_POS_DIMENSION = "dimension"
        const val GLOBAL_POS = "pos"
        const val BLOCK_POS_LENGTH = 3
        const val LEGACY_SPAWN_DIMENSION = "SpawnDimension"
        const val LEGACY_SPAWN_X = "SpawnX"
        const val LEGACY_SPAWN_Y = "SpawnY"
        const val LEGACY_SPAWN_Z = "SpawnZ"

        const val SECTIONS = "sections"
        const val SECTION_Y = "Y"
        const val BLOCK_STATES = "block_states"
        const val PALETTE = "palette"
        const val DATA = "data"
        const val BLOCK_NAME = "Name"

        /** The two blocks a player can wake up on. */
        const val RESPAWN_ANCHOR = "minecraft:respawn_anchor"
        const val BED_SUFFIX = "_bed"

        const val CHUNK_SHIFT = 4
        const val REGION_SHIFT = 5
        const val SECTION_SHIFT = 4

        /** Blocks per axis in a section, as a mask and as the bits an index gives each axis. */
        const val BLOCK_MASK = 15
        const val AXIS_BITS = 4

        /** A section palette is never indexed with fewer than four bits, however small it is. */
        const val MINIMUM_BITS = 4
        const val PALETTE_INDEX_BITS = Int.SIZE_BITS
    }
}
