package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.ChunkPos

/**
 * The first half of the merge: an operator asks where Secondary would go and
 * gets a real answer, with nothing written and nothing to undo (merge spec,
 * User Stories 1–7).
 *
 * Every number asserted here is arrived at by hand from
 * [MergedDeploymentFixture.build]'s geography, so a test that goes red says the
 * arithmetic changed rather than that a golden file drifted.
 */
class WorldMergeTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        /**
         * The first region file column the relocation can land in. Primary's own
         * data is at (−1,−1)…(0,0), and Secondary arrives at 2 and beyond in the
         * nether and 16 and beyond in the overworld — so this tells relocated
         * files from Primary's own without a test having to name any of them.
         */
        private const val RELOCATED_FROM = 2
    }

    @TempDir
    lateinit var dir: Path

    private lateinit var save: MergedDeploymentFixture

    @BeforeEach
    fun buildDeployment() {
        save = MergedDeploymentFixture(dir).build()
    }

    /** Where Secondary would go. Writes nothing; see [MergedDeploymentFixture.plan]. */
    private fun merge(
        clearance: Int = WorldMerge.DEFAULT_CLEARANCE,
        offset: MergeOffset? = null,
        searchLimit: Int = WorldMerge.DEFAULT_SEARCH_LIMIT,
    ) = WorldMerge(save.plan(clearance, offset, searchLimit)).run().placement

    /** The whole merge, run for real against Secondary's real chunk data. */
    private fun relocate(offset: MergeOffset? = null) =
        WorldMerge(save.plan(offset = offset, planOnly = false)).run()

    // ---- what the operator sees ---------------------------------------------

    @Test
    fun `the plan states the offset, both footprints and the clearance it achieved`() {
        val placement = merge()

        assertEquals(
            listOf(
                "offset                   : x +8192, z +0  (nether x +1024, z +0)",
                "offset came from         : the search — the nearest clear slot, 9 tried",
                "clearance asked for      : 512 nether blocks, 4096 in the overworld",
                "overworld",
                "  Secondary's footprint  : x 0…1023  z 0…511  (2 region files)",
                "  lands at               : x 8192…9215  z 0…511",
                "  clearance achieved     : 7680 blocks",
                "  Primary has reached    : x -512…511  z -512…511",
                "nether",
                "  Secondary's footprint  : x 0…511  z 0…511  (1 region file)",
                "  lands at               : x 1024…1535  z 0…511",
                "  clearance achieved     : 512 blocks",
                "  Primary has reached    : x 0…511  z 0…511",
            ),
            placement.lines(),
        )
    }

    @Test
    fun `planning writes nothing at all`() {
        val before = save.contents()

        merge()

        assertEquals(before, save.contents())
    }

    // ---- the geometry -------------------------------------------------------

    @Test
    fun `the offset is a multiple of 4096 on both horizontal axes`() {
        val offset = merge().offset

        assertEquals(0, offset.x % MergeGeometry.OFFSET_ALIGNMENT)
        assertEquals(0, offset.z % MergeGeometry.OFFSET_ALIGNMENT)
    }

    @Test
    fun `an offset off the 4096 lattice is not a merge offset at all`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) { MergeOffset(4096, 100) }

        assertEquals(
            "a merge offset must be a multiple of 4096 blocks on both axes, so that every source " +
                "region file lands on exactly one destination region file — got x 4096, z 100",
            refusal.message,
        )
    }

    @Test
    fun `the nether moves exactly one eighth as far, so portal pairs still link`() {
        val offset = merge().offset

        assertEquals(offset.x / 8, offset.shiftX(DimensionRole.NETHER))
        assertEquals(offset.z / 8, offset.shiftZ(DimensionRole.NETHER))
        assertEquals(offset.x, offset.shiftX(DimensionRole.OVERWORLD))
        assertEquals(offset.z, offset.shiftZ(DimensionRole.OVERWORLD))
    }

    @Test
    fun `a Secondary coordinate becomes a merged one by moving on x and z only`() {
        val offset = MergeOffset(8192, -4096)

        assertEquals(8292, offset.mergedX(100, DimensionRole.OVERWORLD))
        assertEquals(-4096, offset.mergedZ(0, DimensionRole.OVERWORLD))
        assertEquals(1124, offset.mergedX(100, DimensionRole.NETHER))
        assertEquals(-512, offset.mergedZ(0, DimensionRole.NETHER))
    }

    @Test
    fun `the measured footprint says whether a coordinate was inside Secondary`() {
        // The question the audit asks of every relocated chunk, and the claim
        // path asks of every returning save.
        val secondary = merge().dimension(DimensionRole.OVERWORLD).secondary

        assertTrue(secondary.containsBlock(0, 0))
        assertTrue(secondary.containsBlock(1023, 511))
        assertFalse(secondary.containsBlock(1024, 0))
        assertFalse(secondary.containsBlock(0, -1))
    }

    @Test
    fun `Secondary's End has no place in the geometry, because the merge discards it`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            MergeOffset(8192, 0).shiftX(DimensionRole.END)
        }

        assertEquals(
            "Secondary's End is discarded by the merge rather than relocated, so it has no offset",
            refusal.message,
        )
    }

    // ---- the search ---------------------------------------------------------

    @Test
    fun `the nearest viable slot wins`() {
        // Every slot at one and two steps out crowds Primary's origin; (2, 0) is
        // the ninth tried and the first that clears it.
        val placement = merge()

        assertEquals(MergeOffset(8192, 0), placement.offset)
        assertEquals(9, placement.slotsConsidered)
    }

    @Test
    fun `a smaller clearance lets Secondary sit closer in`() {
        val placement = merge(clearance = 0)

        assertEquals(MergeOffset(4096, 0), placement.offset)
        assertEquals(1, placement.slotsConsidered)
        assertEquals(3584, placement.dimension(DimensionRole.OVERWORLD).clearanceAchieved)
        assertEquals(0, placement.dimension(DimensionRole.NETHER).clearanceAchieved)
    }

    @Test
    fun `the overworld ring is eight times the nether's, because clearance is stated in nether blocks`() {
        // 4096 overworld blocks of ring reach this file; 512 would not, and the
        // nearer slot on +x would then have won.
        save.primary(DimensionRole.OVERWORLD, "region", 20 to 0)

        val placement = merge()

        assertEquals(MergeOffset(0, 8192), placement.offset)
    }

    @Test
    fun `entity data alone is enough to rule a slot out`() {
        save.primary(DimensionRole.OVERWORLD, "entities", 10 to 0)

        assertEquals(MergeOffset(0, 8192), merge().offset)
    }

    @Test
    fun `point-of-interest data alone is enough to rule a slot out`() {
        save.primary(DimensionRole.OVERWORLD, "poi", 10 to 0)

        assertEquals(MergeOffset(0, 8192), merge().offset)
    }

    @Test
    fun `the nether's own footprint can rule a slot out by itself`() {
        save.primary(DimensionRole.NETHER, "region", 2 to 0)

        assertEquals(MergeOffset(0, 8192), merge().offset)
    }

    // ---- landing clear of Secondary's own ground ----------------------------
    //
    // The audit asks "does this coordinate still point into Secondary's old
    // footprint?" to decide whether it moved, and inside an overlap between the
    // landed footprint and the old one that question has no answer. So a slot
    // that lands Secondary on itself is not a slot, however clear of Primary it
    // is (ticket 03's judgement call 5; ticket 18).

    @Test
    fun `a slot that lands Secondary's overworld back on itself is not a slot`() {
        // Secondary's overworld now spans region files 0…16, and the overworld
        // moves 8 region files per lattice step — so (2, 0), the ninth slot and
        // the first one clear of Primary, lands it on files 16…32 and overlaps
        // the file it came from. The search goes on to (0, 2), which does not.
        save.secondary(DimensionRole.OVERWORLD, "region", 16 to 0)

        val placement = merge()

        assertEquals(MergeOffset(0, 8192), placement.offset)
        assertEquals(10, placement.slotsConsidered)
    }

    @Test
    fun `a slot that lands Secondary's nether back on itself is not a slot either`() {
        // The same argument in the nether, which moves one region file per step:
        // spanning files 0…5 there is enough for (2, 0) to land on 2…7. The
        // overworld clears its own ground at that slot, so the nether rules it
        // out alone.
        save.secondary(DimensionRole.NETHER, "region", 5 to 0)

        val placement = merge()

        assertEquals(MergeOffset(0, 8192), placement.offset)
        assertEquals(10, placement.slotsConsidered)
    }

    // ---- an offset the operator supplies -------------------------------------

    @Test
    fun `a supplied offset is checked by the same test a searched one passes`() {
        val placement = merge(offset = MergeOffset(8192, 0))

        assertEquals(MergeOffset(8192, 0), placement.offset)
        assertTrue(placement.supplied)
        assertEquals(
            "offset came from         : --offset, checked rather than trusted",
            placement.lines()[1],
        )
    }

    @Test
    fun `a supplied offset whose footprint is not clear is refused by name`() {
        val refusal = assertThrows(MigrationRefused::class.java) { merge(offset = MergeOffset(4096, 0)) }

        assertEquals(
            "the offset x +4096, z +0 does not clear Primary's chunk data: Secondary's overworld " +
                "would come within 4096 blocks of Primary's r.0.-1.mca and r.0.0.mca — " +
                "choose another offset, or ask for less clearance",
            refusal.message,
        )
    }

    @Test
    fun `a supplied offset that lands Secondary on its own ground is refused by name`() {
        // Clear of Primary — the search itself would have taken this offset
        // before ticket 18 — and still not far enough to have left.
        save.secondary(DimensionRole.NETHER, "region", 5 to 0)

        val refusal = assertThrows(MigrationRefused::class.java) { merge(offset = MergeOffset(8192, 0)) }

        assertEquals(
            "the offset x +8192, z +0 would set Secondary's nether back down on ground it already " +
                "covers: it lands on x 1024…4095  z 0…511, and Secondary's nether is at " +
                "x 0…3071  z 0…511. Inside that overlap the audit cannot tell a coordinate that " +
                "moved from one that never left, so the landmass has to clear the place it is " +
                "being moved off",
            refusal.message,
        )
    }

    @Test
    fun `an offset that would leave Secondary where it is refuses`() {
        val refusal = assertThrows(MigrationRefused::class.java) { merge(offset = MergeOffset(0, 0)) }

        assertEquals(
            "an offset of x +0, z +0 would leave Secondary exactly where it is — the landmass has to move",
            refusal.message,
        )
    }

    // ---- refusals -----------------------------------------------------------

    @Test
    fun `no slot clearing the requested distance refuses, naming what it found`() {
        val refusal = assertThrows(MigrationRefused::class.java) {
            merge(clearance = 100_000, searchLimit = 2)
        }

        // Every slot was lost to Primary and none to Secondary's own ground, and
        // the tally says so: this is the operator's evidence that less clearance
        // is the lever to pull.
        assertEquals(
            "no 4096-aligned slot within 8192 blocks of the origin can take Secondary — 24 slots " +
                "tried, 0 of them ruled out by the ground Secondary is being moved off and 24 by " +
                "Primary's chunk data (100000 nether blocks of clearance), and Primary's overworld " +
                "reaches x -512…511  z -512…511; Primary's nether reaches x 0…511  z 0…511. " +
                "Ask for less clearance, or search further out",
            refusal.message,
        )
    }

    @Test
    fun `no slot clear of Secondary's own ground refuses, and says that is what it ran out of`() {
        // Secondary's nether now spans region files (0,0)…(5,5), so every slot
        // within one step of the origin — the nether moves one region file per
        // step — would set it back down on top of itself. None of the eight is
        // ruled out by Primary at all, and the tally is what tells the operator
        // that asking for less clearance would change nothing.
        save.secondary(DimensionRole.NETHER, "region", 5 to 5)

        val refusal = assertThrows(MigrationRefused::class.java) { merge(searchLimit = 1) }

        assertEquals(
            "no 4096-aligned slot within 4096 blocks of the origin can take Secondary — 8 slots " +
                "tried, 8 of them ruled out by the ground Secondary is being moved off and 0 by " +
                "Primary's chunk data (512 nether blocks of clearance), and Primary's overworld " +
                "reaches x -512…511  z -512…511; Primary's nether reaches x 0…511  z 0…511. " +
                "Ask for less clearance, or search further out",
            refusal.message,
        )
    }

    @Test
    fun `a save that already carries the merge stamp refuses`() {
        save.stampAsMerged()

        val refusal = assertThrows(MigrationRefused::class.java) { merge() }

        assertEquals(
            "${save.targetDir} has already been merged: {\"mergedAt\":\"2026-01-01T00:00:00Z\"}",
            refusal.message,
        )
    }

    @Test
    fun `a staging directory left by an interrupted run refuses and is left in place`() {
        Files.createDirectories(save.staging)

        val refusal = assertThrows(MigrationRefused::class.java) { merge() }

        assertEquals(
            "${save.staging} is left over from an interrupted merge; " +
                "look at what it holds, then remove it and run again",
            refusal.message,
        )
        assertTrue(Files.isDirectory(save.staging))
    }

    @Test
    fun `a target that is not a directory refuses`() {
        val plan = save.plan().copy(targetDir = dir.resolve("nowhere"))

        val refusal = assertThrows(MigrationRefused::class.java) { WorldMerge(plan).run() }

        assertEquals("${dir.resolve("nowhere")} is not a directory", refusal.message)
    }

    @Test
    fun `a run directory the migration never wrote refuses`() {
        Files.delete(save.regionsFile)

        val refusal = assertThrows(MigrationRefused::class.java) { merge() }

        assertEquals(
            "${save.targetDir} has no \"regions.json\" — merge the live server's own run directory " +
                "(see docs/migration.md), not a fresh one",
            refusal.message,
        )
    }

    @Test
    fun `a save holding no Secondary chunk data refuses`() {
        save.forgetSecondary()

        val refusal = assertThrows(MigrationRefused::class.java) { merge() }

        assertEquals(
            "no Secondary chunk data under ${save.levelDir.resolve("dimensions/mctraveler/secondary")} " +
                "or ${save.levelDir.resolve("dimensions/mctraveler/secondary_nether")} — " +
                "is ${save.targetDir} the run directory the Portal migration produced?",
            refusal.message,
        )
    }

    @Test
    fun `every refusal leaves the run directory exactly as it was`() {
        val before = save.contents()

        assertThrows(MigrationRefused::class.java) { merge(offset = MergeOffset(4096, 0)) }

        assertEquals(before, save.contents())
    }

    // ---- relocating the chunks ----------------------------------------------
    //
    // These run the real MCA Selector, resolved and checksum-verified by the
    // build, against real region files. Nothing here is stubbed, so a green test
    // is evidence the tool relocated the chunks rather than evidence the merge
    // believes it did (merge spec, "Testing Decisions").
    //
    // Secondary's overworld sits in region files (0,0) and (1,0) and the search
    // sends it 8192 blocks east, which is 512 chunks and 16 region files. The
    // nether gets an eighth of that: 1024 blocks, 64 chunks, 2 region files.

    @Test
    fun `Secondary's overworld chunks land where Primary's overworld will look for them`() {
        save.withRealSecondaryChunks()

        relocate()

        assertEquals(
            setOf(ChunkPos(512, 0), ChunkPos(517, 3), ChunkPos(544, 0)),
            relocatedChunks(DimensionRole.OVERWORLD, "region"),
        )
    }

    @Test
    fun `each source region file lands on one destination region file of its own`() {
        save.withRealSecondaryChunks()

        relocate()

        // (0,0) carries two chunks and (1,0) one, so a merge that funnelled
        // everything into a single file would still have the right chunk count.
        assertEquals(
            listOf("r.16.0.mca", "r.17.0.mca"),
            regionFileNames(DimensionRole.OVERWORLD, "region"),
        )
    }

    @Test
    fun `Secondary's nether moves exactly one eighth as far, so portal pairs still link`() {
        save.withRealSecondaryChunks()

        relocate()

        assertEquals(setOf(ChunkPos(64, 0)), relocatedChunks(DimensionRole.NETHER, "region"))
        assertEquals(listOf("r.2.0.mca"), regionFileNames(DimensionRole.NETHER, "region"))
    }

    @Test
    fun `terrain, entity and point-of-interest data all move together`() {
        save.withRealSecondaryChunks()

        relocate()

        val expected = setOf(ChunkPos(512, 0), ChunkPos(517, 3), ChunkPos(544, 0))
        assertEquals(expected, relocatedChunks(DimensionRole.OVERWORLD, "region"))
        assertEquals(expected, relocatedChunks(DimensionRole.OVERWORLD, "entities"))
        assertEquals(expected, relocatedChunks(DimensionRole.OVERWORLD, "poi"))
    }

    @Test
    fun `a relocated chunk carries its own new coordinates, not its old ones`() {
        save.withRealSecondaryChunks()

        relocate()

        val chunk = SyntheticChunks
            .read(save.primaryStorage(DimensionRole.OVERWORLD).resolve("region"), "chunk", overworld)
            .getValue(ChunkPos(517, 3))

        assertEquals(517, chunk.getIntOr("xPos", -1))
        assertEquals(3, chunk.getIntOr("zPos", -1))
        // The chest in that chunk was at block x 80; it has to have moved with it.
        assertEquals(
            80 + 8192,
            chunk.getListOrEmpty("block_entities").getCompoundOrEmpty(0).getIntOr("x", -1),
        )
    }

    @Test
    fun `a chunk vanilla never finished is dropped rather than relocated`() {
        save.withRealSecondaryChunks()

        val report = relocate()

        // The frontier chunk would have landed at (519, 519); the whole point is
        // that the frontier regenerates from Primary's seed instead.
        assertFalse(ChunkPos(519, 519) in relocatedChunks(DimensionRole.OVERWORLD, "region"))
        assertEquals(1, report.relocation.dimension(DimensionRole.OVERWORLD).dropped)
    }

    @Test
    fun `Secondary's End and its level-wide saved data are discarded rather than moved`() {
        save.withRealSecondaryChunks()

        val report = relocate()

        assertEquals(
            listOf("Secondary's End", "Secondary's level-wide saved data"),
            report.relocation.discarded.map { it.what },
        )
        // Nothing of the End reached Primary, in any dimension.
        assertTrue(Files.notExists(save.primaryStorage(DimensionRole.END).resolve("region")))
    }

    @Test
    fun `the report states what was relocated, what was dropped and what it cost`() {
        save.withRealSecondaryChunks()

        val report = relocate()

        assertEquals(
            listOf(
                "overworld",
                "  chunks relocated       : 3",
                "  chunks dropped         : 1 (not fully generated)",
                "  files written          : 6",
                "  bytes transferred      : ${bytesIn(DimensionRole.OVERWORLD)}",
                "nether",
                "  chunks relocated       : 1",
                "  chunks dropped         : 0 (not fully generated)",
                "  files written          : 3",
                "  bytes transferred      : ${bytesIn(DimensionRole.NETHER)}",
                "relocated in total       : 4 chunks, 1 dropped, " +
                    "${bytesIn(DimensionRole.OVERWORLD) + bytesIn(DimensionRole.NETHER)} bytes",
                "discarded                : Secondary's End — 1 file",
                "discarded                : Secondary's level-wide saved data — 1 file",
            ),
            report.relocation.lines(),
        )
    }

    @Test
    fun `the report is the placement as well, so the operator sees where it went`() {
        save.withRealSecondaryChunks()

        val report = relocate()

        assertEquals(MergeOffset(8192, 0), report.offset)
        assertEquals("offset                   : x +8192, z +0  (nether x +1024, z +0)", report.lines().first())
    }

    // ---- the staging discipline ---------------------------------------------

    @Test
    fun `a completed merge leaves no staging directory behind`() {
        save.withRealSecondaryChunks()

        relocate()

        assertFalse(Files.exists(save.staging))
    }

    @Test
    fun `a completed merge stamps the save with the offset it used`() {
        save.withRealSecondaryChunks()

        relocate()

        val stamp = Files.readString(save.mergeStamp)
        assertTrue(stamp.contains("\"offsetX\":8192"), stamp)
        assertTrue(stamp.contains("\"offsetZ\":0"), stamp)
    }

    @Test
    fun `a merged save refuses to be merged a second time`() {
        save.withRealSecondaryChunks()
        relocate()

        val refusal = assertThrows(MigrationRefused::class.java) { merge() }

        assertTrue(refusal.message!!.startsWith("${save.targetDir} has already been merged: "), refusal.message)
    }

    @Test
    fun `Primary's own chunk data is never opened, let alone changed`() {
        // Primary's region files hold nonsense no chunk reader could parse. The
        // merge stages into empty destination files and moves them in, so it
        // never has cause to look — and this passing is what says so.
        save.withRealSecondaryChunks()
        val before = primaryChunkBytes()

        relocate()

        assertEquals(before, primaryChunkBytes())
    }

    @Test
    fun `Secondary's own chunk data is left exactly as it was, because the merge only copies`() {
        // `--worlds move` is deliberately not offered: the pre-merge backup is
        // the rollback, and a moved source would compromise it (merge spec,
        // "Further Notes").
        save.withRealSecondaryChunks()
        val before = secondaryChunkData()

        relocate()

        assertEquals(before, secondaryChunkData())
    }

    @Test
    fun `a failing relocation tool fails the merge with its own output, and moves nothing`() {
        save.withRealSecondaryChunks()
        val before = save.contents()
        val placement = merge()
        val broken = dir.resolve("not-really-a.jar")
        Files.writeString(broken, "this is not a jar")

        val failure = assertThrows(IllegalStateException::class.java) {
            MergeStaging(
                save.plan(planOnly = false),
                save.staging,
                save.levelDir,
                McaSelector(broken),
            ).write(placement)
        }

        assertTrue(failure.message!!.startsWith("MCA Selector failed while "), failure.message)
        assertTrue(failure.message!!.contains("Nothing has been moved into place."), failure.message)
        assertEquals(before, save.contents())
        assertFalse(Files.exists(save.staging))
    }

    @Test
    fun `the merge refuses to run without the tool the build resolves`() {
        val was = System.getProperty(McaSelector.JAR_PROPERTY)
        System.clearProperty(McaSelector.JAR_PROPERTY)
        try {
            val failure = assertThrows(IllegalStateException::class.java) { McaSelector.resolved() }

            assertTrue(
                failure.message!!.contains("the merge does not know where MCA Selector is"),
                failure.message,
            )
        } finally {
            was?.let { System.setProperty(McaSelector.JAR_PROPERTY, it) }
        }
    }

    // ---- reading the merged save back ---------------------------------------

    private val overworld get() = save.primaryDimension(DimensionRole.OVERWORLD)

    private fun relocatedChunks(role: DimensionRole, folder: String): Set<ChunkPos> =
        SyntheticChunks.positions(save.primaryStorage(role).resolve(folder), folder, save.primaryDimension(role))

    /** The region files the merge added to [role], leaving Primary's own out of it. */
    private fun regionFileNames(role: DimensionRole, folder: String): List<String> =
        Files.newDirectoryStream(save.primaryStorage(role).resolve(folder), "r.*.mca")
            .use { files -> files.map { it.fileName.toString() }.toList() }
            .filter { RegionFilePos.parse(it)!!.x >= RELOCATED_FROM }
            .sorted()

    /** The bytes of relocated chunk data now in [role], as the report should have counted them. */
    private fun bytesIn(role: DimensionRole): Long = Footprint.CHUNK_DIRECTORIES
        .map { save.primaryStorage(role).resolve(it) }
        .filter(Files::isDirectory)
        .flatMap { folder -> Files.newDirectoryStream(folder, "r.*.mca").use { it.toList() } }
        .filter { RegionFilePos.parse(it.fileName.toString())!!.x >= RELOCATED_FROM }
        .sumOf(Files::size)

    /** Every file of Secondary's, by path and content, so a rewrite of any of them shows up. */
    private fun secondaryChunkData(): Map<String, String> {
        val found = sortedMapOf<String, String>()
        for (role in DimensionRole.entries) {
            val storage = Footprint.storageFolder(save.levelDir, save.secondaryDimension(role))
            if (!Files.isDirectory(storage)) continue
            Files.walk(storage).use { paths ->
                paths.filter(Files::isRegularFile).forEach { file ->
                    found[save.levelDir.relativize(file).toString()] = digestOf(file)
                }
            }
        }
        return found
    }

    private fun digestOf(file: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(file))
        .joinToString("") { "%02x".format(it) }

    /** Every chunk file Primary had before the merge, by path and content. */
    private fun primaryChunkBytes(): Map<String, String> {
        val found = sortedMapOf<String, String>()
        for (role in DimensionRole.entries) {
            val storage = save.primaryStorage(role)
            if (!Files.isDirectory(storage)) continue
            Files.walk(storage).use { paths ->
                paths.filter(Files::isRegularFile)
                    .filter { RegionFilePos.parse(it.fileName.toString())!!.x < RELOCATED_FROM }
                    .forEach { found[save.levelDir.relativize(it).toString()] = Files.readString(it) }
            }
        }
        return found
    }

}
