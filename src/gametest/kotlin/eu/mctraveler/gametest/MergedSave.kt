package eu.mctraveler.gametest

import eu.mctraveler.importer.Footprint
import eu.mctraveler.importer.MergeOffset
import eu.mctraveler.importer.MergePlan
import eu.mctraveler.importer.MergeReport
import eu.mctraveler.importer.RegionFilePos
import eu.mctraveler.importer.WorldLayout
import eu.mctraveler.importer.WorldMerge
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionService
import eu.mctraveler.worlds.BankedPositions
import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.NetherPortalBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.Vec3

/**
 * Secondary merged into Primary for real, in a run directory of its own, with
 * what came out laid into the server this gametest is running on (ticket 11).
 *
 * **The fixture is the merge's own output, and it has to be.** A save built by
 * hand with `dimensions/mctraveler/secondary/` in it would not fail on this
 * server — it would be *ignored*, silently, because the build that retired the
 * Worlds subsystem stopped the server creating those dimensions at all (ticket
 * 09). A test standing on such a fixture would go green while proving nothing.
 * So everything the cases below read back is something the merge put in
 * Primary's own dimension folders, and [build] asserts those folders were empty
 * before it ran.
 *
 * The chunks the merge relocates are chunks **this server wrote**. A homestead
 * is built in the live overworld and a nether portal's twin in the live nether,
 * both are saved through vanilla's own writer, and the region files are copied
 * into a temporary save as Secondary's. That is the one way to be sure the
 * relocated chunk a case loads is a chunk a server can load: chunk NBT a test
 * invented could pass a file-level comparison and still be unreadable to the
 * game, which is precisely the gap this ticket exists to close.
 *
 * One merge serves every case. It is real work — two MCA Selector subprocesses
 * per dimension, an audit, a block-for-block sampled diff and every sweep — and
 * running it once is also the truer shape: a cutover happens once and is then
 * observed from many directions.
 */
class MergedSave private constructor(
    /** The run directory the merge ran against, holding everything it wrote. */
    val runDir: Path,
    /** What the merge said it did, so a case can hold the operator's own account to account. */
    val report: MergeReport,
) {

    val levelDir: Path get() = runDir.resolve("world")

    /** Where the merge put [role]'s relocated chunk data — one of Primary's own dimensions. */
    fun primaryStorage(role: DimensionRole): Path =
        Footprint.storageFolder(levelDir, WorldLayout.PRIMARY.dimension(role))

    /**
     * Where Secondary's chunk data still is. The merge only ever copies — the
     * pre-merge backup is the rollback — so a merged run directory still holds
     * the dimensions this server can no longer create, and that is the trap in
     * the class note stated as a fact about the fixture.
     */
    fun secondaryStorage(role: DimensionRole): Path =
        Footprint.storageFolder(levelDir, WorldLayout.SECONDARY.dimension(role))

    /** The swept `regions.json`, read back with the very reader the live server uses. */
    fun regions(): List<Region> = RegionService(runDir.resolve("regions.json")).roots

    /** The artifact the merge left for the `/switch` signpost, if it banked anybody. */
    fun bankedPositions(): Path = runDir.resolve("mctraveler/${BankedPositions.FILE_NAME}")

    /** [uuid]'s save as the player sweep rewrote it. */
    fun playerSave(uuid: UUID): Path = levelDir.resolve("playerdata/$uuid.dat")

    /**
     * The relocated chunk data copied into the dimensions this server actually
     * has, which is the whole of what "booting on a merged save" can mean inside
     * a gametest: there is one server and it is already running, so the merged
     * save is brought to it rather than the other way round. `MigrationGameTest`
     * and `OrphanedSaveClaimGameTest` reach a live server the same way.
     *
     * Only Primary's dimensions are copied, because only Primary's exist here.
     * Secondary's folders stay in [runDir] untouched, which is the trap in the
     * class note made harmless: what this server is given is exactly the half of
     * the merged save it is able to read.
     *
     * A merge that relocated nothing would leave nothing to copy, and this
     * refuses rather than letting every case below quietly assert things about
     * terrain the server generated for itself.
     */
    private fun layIntoThisServer(server: MinecraftServer) {
        val liveLevelDir = server.getWorldPath(LevelResource.ROOT)
        var copied = 0
        for (role in listOf(DimensionRole.OVERWORLD, DimensionRole.NETHER)) {
            val into = Footprint.storageFolder(liveLevelDir, WorldLayout.PRIMARY.dimension(role))
            for (folder in Footprint.CHUNK_DIRECTORIES) {
                val from = primaryStorage(role).resolve(folder)
                if (!Files.isDirectory(from)) continue
                Files.newDirectoryStream(from, "r.*.mca").use { files ->
                    for (file in files) {
                        val destination = into.resolve(folder).resolve(file.fileName.toString())
                        Files.createDirectories(destination.parent)
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
                        copied++
                    }
                }
            }
        }
        check(copied > 0) {
            "the merge left no relocated chunk data in $levelDir to lay into this server, so nothing " +
                "the cases read back could have come from it"
        }
    }

    companion object {

        /**
         * How far Secondary moves: three lattice steps out, in opposite
         * directions on the two axes so that a transposed or sign-flipped
         * coordinate cannot pass by accident.
         *
         * Three rather than one because a chunk generated to full status drags
         * its neighbours with it, and Secondary's footprint here is therefore a
         * whole region file in each dimension. One step is a single region file
         * in the nether, and the placement search refuses an offset that sets a
         * dimension back down on ground it already covers (ticket 18) — which is
         * exactly what a one-step nether move would do.
         */
        val OFFSET = MergeOffset(12288, -12288)

        /**
         * The chunk Secondary's homestead stands in, chosen so that everything
         * vanilla generates around it stays inside one region file: chunk 1360
         * sits at index 16 of region file 42, and full generation reaches eight
         * chunks out at most.
         */
        val HOMESTEAD = ChunkPos(1360, 1360)

        /**
         * The chunk the nether twin stands in — chunk 172, index 12 of region
         * file 5, well clear of that file's edges for the same reason.
         *
         * It is where it is because a nether portal links at one eighth of the
         * overworld's coordinates: the homestead's portal scales onto this chunk,
         * and the offset's own ÷8 is what has to keep the pair together.
         */
        val TWIN = ChunkPos(172, 172)

        /** The ground the homestead stands on, and the room cleared above it. */
        const val FLOOR_Y = 64
        const val HEADROOM = 6

        // Secondary's own coordinates. Everything below is what the merge is
        // handed; what the cases assert is each of these put through [merged].

        /** The bottom-west block of the homestead's lit portal, which runs east. */
        val PORTAL = BlockPos(21761, 65, 21760)

        /** The chest, and what is in it — the container whose contents have to arrive. */
        val CHEST = BlockPos(21765, 65, 21765)
        const val DIAMONDS = 7

        /** The bed the settler sleeps in, whose foot their respawn point names. */
        val BED = BlockPos(21769, 65, 21769)

        /** A block inside the Region for a stranger to try to break. */
        val STONE = BlockPos(21773, 65, 21773)

        /** Where the settler logs out, a few blocks off their bed. */
        val STANDING = Vec3(21769.5, 65.0, 21771.5)

        /**
         * The nether portal the homestead's own links to, deliberately not at
         * the scaled position: it sits 33 blocks east and 34 south of where the
         * overworld portal divides down to, so a vanilla search that failed to
         * find it and dug a fresh one instead could not land on these
         * coordinates by chance.
         */
        val TWIN_PORTAL = BlockPos(2753, 65, 2754)

        /** Where the signpost player's Secondary base was, before it moved. */
        val BANKED = Vec3(21765.5, 65.0, 21765.5)

        /** The Region over the homestead, recorded against Secondary's own world string. */
        const val REGION_TITLE = "Secondary Homestead"
        val REGION_MIN: Int get() = HOMESTEAD.minBlockX
        val REGION_MAX: Int get() = HOMESTEAD.maxBlockX

        /** The Region's one member, who is deliberately nobody who logs in below. */
        val HOMESTEADER: UUID = UUID.fromString("1a2b3c4d-5e6f-4708-8910-a1b2c3d4e5f6")

        /** The player who was last in Secondary, and who has to wake up on their own bed. */
        val SETTLER: UUID = UUID.fromString("2b3c4d5e-6f70-4819-9a21-b2c3d4e5f607")
        const val SETTLER_NAME = "T11Settler"

        /** The player who was last in Primary, and whose Secondary base the signpost names. */
        val SIGNPOST: UUID = UUID.fromString("3c4d5e6f-7081-492a-8b32-c3d4e5f60718")
        const val SIGNPOST_NAME = "T11Signpost"

        /** Where [at] ends up once the merge has moved [role]. */
        fun merged(at: BlockPos, role: DimensionRole): BlockPos =
            BlockPos(OFFSET.mergedX(at.x, role), at.y, OFFSET.mergedZ(at.z, role))

        /** Where [at] ends up once the merge has moved [role], to the fraction of a block. */
        fun merged(at: Vec3, role: DimensionRole): Vec3 =
            Vec3(OFFSET.mergedX(at.x, role), at.y, OFFSET.mergedZ(at.z, role))

        private var merged: MergedSave? = null

        /**
         * The merge, run once for this server and then read back by every case.
         *
         * It blocks the server thread for as long as the whole operation takes,
         * which is what a merge is. Ticks do not advance while it runs, so no
         * case's tick budget is spent on it.
         */
        fun of(server: MinecraftServer): MergedSave =
            merged ?: build(server).also { merged = it }

        /**
         * The whole operation: a two-World save assembled out of chunks this
         * server wrote, the merge run against it, and what came out laid into
         * this server's own dimensions.
         *
         * The order is the cutover's own. Everything Secondary is put on disk
         * first, because the merge reads a stopped server's run directory and
         * has to find one; the merge runs; and only then is anything handed to
         * the live server, which is the point at which the game gets to have an
         * opinion about what the tool produced.
         */
        private fun build(server: MinecraftServer): MergedSave {
            val root = Files.createTempDirectory("mctraveler-merge-gametest")
            // Cleaned up when the run ends rather than when a case does: one
            // merge serves every case, so no case owns it, and a JVM that dies
            // mid-suite leaves the whole thing standing for a post-mortem —
            // which is what the merge's own staging discipline does too.
            Runtime.getRuntime().addShutdownHook(Thread { deleteRecursively(root) })

            val runDir = root.resolve("run")
            Files.createDirectories(runDir.resolve("mctraveler/players"))
            Files.writeString(runDir.resolve("regions.json"), regionsJson())
            val levelDir = runDir.resolve("world")

            writeSecondarysChunks(server, levelDir)
            writeSettlersSave(server, levelDir)
            Files.writeString(runDir.resolve("mctraveler/players/$SIGNPOST.json"), signpostRecord())

            // The claim the whole suite rests on, asserted rather than assumed:
            // Primary's dimensions hold nothing at all going in, so anything a
            // booted server reads out of them afterwards was put there by the
            // merge and by nothing else.
            for (role in listOf(DimensionRole.OVERWORLD, DimensionRole.NETHER)) {
                val primary = Footprint.storageFolder(levelDir, WorldLayout.PRIMARY.dimension(role))
                check(Files.notExists(primary)) {
                    "$primary already holds chunk data before the merge has run, so nothing below " +
                        "would be evidence that the merge put it there"
                }
            }

            val merged = MergedSave(runDir, WorldMerge(MergePlan(targetDir = runDir, offset = OFFSET)).run())
            merged.layIntoThisServer(server)
            return merged
        }

        private fun deleteRecursively(directory: Path) {
            if (Files.notExists(directory)) return
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
