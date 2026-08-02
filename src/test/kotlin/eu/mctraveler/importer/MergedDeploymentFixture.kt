package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelData

/**
 * The live server's run directory as the merge finds it on the night: one save
 * holding both Worlds' chunk data, the regions file and the mod's own
 * directory, with the server stopped.
 *
 * The dimension folders are spelled out here rather than asked for, because
 * where the server keeps a dimension's chunk data is precisely what the merge
 * has to get right — a fixture that derived those paths the same way the merge
 * does could only ever agree with it.
 *
 * The chunk files themselves are opaque bytes. Planning a merge never opens
 * one: which region files exist is the whole of what a placement search knows,
 * and it is all it needs (merge spec, "Placement search").
 *
 * A merge that actually relocates needs Secondary's chunk data to be real, and
 * [withRealSecondaryChunks] makes it so. **Primary's stays opaque even then**,
 * which is the point: the merge stages into empty destination files and never
 * opens a Primary chunk, so a test whose Primary is unreadable nonsense and
 * which passes anyway is evidence of exactly that.
 */
class MergedDeploymentFixture(val root: Path) {

    val targetDir: Path = root.resolve("run")

    val levelDir: Path get() = targetDir.resolve("world")
    val regionsFile: Path get() = targetDir.resolve("regions.json")
    val staging: Path get() = targetDir.resolve(".mctraveler-merge")
    val mergeStamp: Path get() = targetDir.resolve("mctraveler/merge.json")

    /**
     * Primary generated around its origin, Secondary a little way off it — big
     * enough that a placement search has to work for its answer, small enough
     * that every number a test asserts can be arrived at by hand.
     *
     * Primary's overworld covers region files (−1,−1)…(0,0), so blocks
     * −512…511 on both axes; its nether covers only (0,0). Secondary's
     * overworld covers (0,0)…(1,0) and its nether (0,0). The End is there to be
     * ignored: the merge discards it rather than placing it.
     */
    fun build(): MergedDeploymentFixture {
        Files.createDirectories(targetDir.resolve("mctraveler/players"))
        write(regionsFile, "{\n  \"regions\": {}\n}\n")
        primary(DimensionRole.OVERWORLD, "region", -1 to -1, -1 to 0, 0 to -1, 0 to 0)
        primary(DimensionRole.OVERWORLD, "entities", 0 to 0)
        primary(DimensionRole.OVERWORLD, "poi", 0 to 0)
        primary(DimensionRole.NETHER, "region", 0 to 0)
        secondary(DimensionRole.OVERWORLD, "region", 0 to 0, 1 to 0)
        secondary(DimensionRole.NETHER, "region", 0 to 0)
        secondary(DimensionRole.END, "region", 0 to 0)
        return this
    }

    /**
     * A plan that only chooses the offset, unless a test asks otherwise.
     *
     * Most of what there is to assert about a merge is decided before anything
     * is written, and planning is also the only thing the opaque chunk files
     * [build] writes can support. A test that wants the relocation to happen
     * says `planOnly = false` and builds real chunk data first.
     */
    fun plan(
        clearance: Int = WorldMerge.DEFAULT_CLEARANCE,
        offset: MergeOffset? = null,
        searchLimit: Int = WorldMerge.DEFAULT_SEARCH_LIMIT,
        planOnly: Boolean = true,
        acceptEndLoss: Boolean = false,
    ) = MergePlan(
        targetDir = targetDir,
        clearance = clearance,
        offset = offset,
        searchLimit = searchLimit,
        planOnly = planOnly,
        acceptEndLoss = acceptEndLoss,
    )

    /**
     * Secondary's opaque chunk files replaced with region files a server could
     * load, in the same region files [build] put them in — so the placement the
     * planning tests assert is the placement the relocation tests get.
     *
     * The overworld spans two region files on purpose: it is the only way to
     * show that each source file lands on one destination file of its own rather
     * than everything being funnelled into one. [FRONTIER] is the half-generated
     * chunk that must not travel.
     */
    fun withRealSecondaryChunks(
        overworld: List<SyntheticChunks.Chunk> = SECONDARY_OVERWORLD,
    ): MergedDeploymentFixture {
        SECONDARY_FOLDERS.values.forEach { deleteRecursively(levelDir.resolve("dimensions/$it")) }
        SyntheticChunks.write(levelDir, secondaryDimension(DimensionRole.OVERWORLD), overworld)
        SyntheticChunks.write(levelDir, secondaryDimension(DimensionRole.NETHER), SECONDARY_NETHER)
        // Discarded rather than relocated, so it never has to be readable.
        secondary(DimensionRole.END, "region", 0 to 0)
        // Maps and raids, which were never imported at the Portal cutover either.
        write(levelDir.resolve("dimensions/${SECONDARY_FOLDERS[DimensionRole.OVERWORLD]}/data/map_0.dat"), "a map")
        return this
    }

    fun secondaryDimension(role: DimensionRole) = WorldLayout.SECONDARY.dimension(role)

    fun primaryDimension(role: DimensionRole) = WorldLayout.PRIMARY.dimension(role)

    /** Where [role]'s relocated chunk data ends up in the live save. */
    fun primaryStorage(role: DimensionRole): Path =
        Footprint.storageFolder(levelDir, primaryDimension(role))

    /** Chunk files in one of Primary's dimensions, at the given region-file coordinates. */
    fun primary(role: DimensionRole, folder: String, vararg files: Pair<Int, Int>) =
        chunks(PRIMARY_FOLDERS.getValue(role), folder, files)

    /** Chunk files in one of Secondary's dimensions, at the given region-file coordinates. */
    fun secondary(role: DimensionRole, folder: String, vararg files: Pair<Int, Int>) =
        chunks(SECONDARY_FOLDERS.getValue(role), folder, files)

    /**
     * The run directory's `regions.json`, exactly as the operator's own file
     * reads. Written verbatim rather than through the live store, so a test can
     * assert that what the merge did not touch came back out unchanged.
     */
    fun withRegions(json: String) = write(regionsFile, json)

    /** What `regions.json` says now — the bytes a booting server would read. */
    fun regionsJson(): String = Files.readString(regionsFile)

    /** The save as it would be if Secondary had never been imported at all. */
    fun forgetSecondary() {
        SECONDARY_FOLDERS.values.forEach { deleteRecursively(levelDir.resolve("dimensions/$it")) }
    }

    /** The stamp a completed merge leaves behind, as a later run must refuse to see. */
    fun stampAsMerged(json: String = """{"mergedAt":"2026-01-01T00:00:00Z"}""") = write(mergeStamp, json)

    /**
     * The save's own spawn, as `level.dat` records it.
     *
     * There is one spawn for both Worlds — one level, one `level.dat` — so this
     * is Secondary's spawn as much as Primary's, and it is where the End gate
     * puts down a player who has no Secondary base of their own to go back to.
     * Written through the game's own codec, which is what the running server
     * writes it with, so what the merge reads back is the shape it will meet.
     */
    fun withWorldSpawn(x: Int, y: Int, z: Int): MergedDeploymentFixture {
        val spawn = LevelData.RespawnData.CODEC.encodeStart(
            NbtOps.INSTANCE,
            LevelData.RespawnData(GlobalPos.of(Level.OVERWORLD, BlockPos(x, y, z)), 0f, 0f),
        ).getOrThrow()
        Files.createDirectories(levelDir)
        NbtIo.writeCompressed(
            CompoundTag().apply { put("Data", CompoundTag().apply { put("spawn", spawn) }) },
            levelDir.resolve("level.dat"),
        )
        return this
    }

    /**
     * Vanilla's own profile cache, in the run directory where a live server
     * keeps it — the file the End gate names Region members out of.
     */
    fun withUserCache(vararg players: Pair<UUID, String>) = write(
        targetDir.resolve("usercache.json"),
        players.joinToString(",", "[", "]") { (uuid, name) ->
            """{"name":"$name","uuid":"$uuid","expiresOn":"2099-01-01 00:00:00 +0000"}"""
        },
    )

    // ---- players ------------------------------------------------------------
    //
    // A player is two files in two different places — vanilla's own save inside
    // the level, and the mod's record beside it — and the merge has to agree with
    // itself about both. Written and read back here by their real spellings, so a
    // test asserts on what a booting server would actually find.

    /** Vanilla's save for [uuid], as `PlayerDataStorage` writes it. */
    fun playerSave(uuid: UUID, tag: CompoundTag) {
        val file = playerdata(uuid, ".dat")
        Files.createDirectories(file.parent)
        NbtIo.writeCompressed(tag, file)
    }

    /** Vanilla's backup of [uuid]'s save — the file `PlayerDataStorage.load` falls back to. */
    fun playerSaveBackup(uuid: UUID, tag: CompoundTag) {
        val file = playerdata(uuid, ".dat_old")
        Files.createDirectories(file.parent)
        NbtIo.writeCompressed(tag, file)
    }

    /** The mod's record for [uuid], verbatim — so a test can put legacy fields in it. */
    fun playerRecord(uuid: UUID, json: String) = write(recordFile(uuid), json)

    fun savedPlayer(uuid: UUID): CompoundTag =
        NbtIo.readCompressed(playerdata(uuid, ".dat"), NbtAccounter.unlimitedHeap())

    fun savedPlayerBackup(uuid: UUID): CompoundTag =
        NbtIo.readCompressed(playerdata(uuid, ".dat_old"), NbtAccounter.unlimitedHeap())

    fun savedRecord(uuid: UUID): String = Files.readString(recordFile(uuid))

    /** The banked positions the merge left for the signpost, or null if it wrote none. */
    fun bankedPositions(): String? =
        targetDir.resolve("mctraveler/banked-positions.json").takeIf(Files::exists)?.let(Files::readString)

    private fun playerdata(uuid: UUID, suffix: String): Path =
        levelDir.resolve("playerdata/$uuid$suffix")

    /** Where [uuid]'s record is, for the tests that read it with the mod's own readers. */
    fun recordFile(uuid: UUID): Path = targetDir.resolve("mctraveler/players/$uuid.json")

    /** Every file in the run directory, by path and size — the whole of what "wrote nothing" means. */
    fun contents(): Map<String, Long> {
        val found = sortedMapOf<String, Long>()
        Files.walk(targetDir).use { paths ->
            paths.filter(Files::isRegularFile).forEach {
                found[targetDir.relativize(it).toString()] = Files.size(it)
            }
        }
        return found
    }

    private fun chunks(dimensionFolder: String, folder: String, files: Array<out Pair<Int, Int>>) {
        for ((x, z) in files) {
            write(levelDir.resolve("dimensions/$dimensionFolder/$folder/r.$x.$z.mca"), "chunk bytes of r.$x.$z.mca")
        }
    }

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun deleteRecursively(directory: Path) {
        if (Files.notExists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        /**
         * Where this server keeps each dimension's chunk data, under the level
         * directory. Every dimension has a folder of its own named after its
         * resource location — the layout the level takes after the first boot
         * (see `docs/migration.md`), the only one the merge will ever meet.
         */
        val PRIMARY_FOLDERS = mapOf(
            DimensionRole.OVERWORLD to "minecraft/overworld",
            DimensionRole.NETHER to "minecraft/the_nether",
            DimensionRole.END to "minecraft/the_end",
        )

        val SECONDARY_FOLDERS = mapOf(
            DimensionRole.OVERWORLD to "mctraveler/secondary",
            DimensionRole.NETHER to "mctraveler/secondary_nether",
            DimensionRole.END to "mctraveler/secondary_end",
        )

        /** The chunk vanilla never finished, at Secondary's frontier. */
        val FRONTIER = SyntheticChunks.Chunk(7, 7, SyntheticChunks.PROTO)

        /**
         * Secondary's overworld: two finished chunks in region file (0,0), one
         * more in (1,0) — chunk 32 is the first chunk of the second file — and
         * the frontier chunk that stays behind.
         */
        val SECONDARY_OVERWORLD = listOf(
            SyntheticChunks.Chunk(0, 0),
            SyntheticChunks.Chunk(5, 3),
            SyntheticChunks.Chunk(32, 0),
            FRONTIER,
        )

        val SECONDARY_NETHER = listOf(SyntheticChunks.Chunk(0, 0))
    }
}
