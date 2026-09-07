package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.name
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceKey
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo

/**
 * The evidence that the terrain arrived (merge spec, User Stories 18–19; ticket
 * 04), driven through the merge command against a synthetic run directory — a
 * sibling of [WorldMergeTest] rather than more of it, because the other phases
 * are extending that class in parallel.
 *
 * The relocation these tests verify is the real one: MCA Selector, resolved and
 * checksum-verified by the build, moving real region files. What makes them tests
 * of the *diff* rather than of the tool is that the tool is then made to get
 * something wrong, one thing at a time, and the merge has to notice.
 *
 * Each of those failures is chosen to be one nothing else in the merge could
 * catch. [ChunkRelocation] counts the chunks that land in the terrain folder
 * against the chunks that were selected, and the audit walks the relocated data
 * for coordinates that still point into Secondary's old footprint — so every
 * failure below leaves the count intact and leaves no stale coordinate behind.
 * A test that damaged the terrain count would only be re-proving ticket 02.
 *
 * Secondary's overworld sits in region files (0,0) and (1,0) and the search sends
 * it 8192 blocks east, which is 512 chunks; every coordinate asserted here is
 * that arithmetic done by hand.
 */
class WorldMergeSampledDiffTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        /** Chunk (0,0) of Secondary's overworld, and where the merge has to put it. */
        private val FIRST = ChunkPos(0, 0)
        private val FIRST_LANDS_AT = ChunkPos(512, 0)

        /** The last chunk of Secondary's overworld, alone in the second region file. */
        private val LAST = ChunkPos(32, 0)
        private val LAST_LANDS_AT = ChunkPos(544, 0)

        /**
         * Where [MergedDeploymentFixture.FRONTIER] would land if it ever
         * travelled: 512 chunks east of (7,7), and not one chunk south, because
         * this fixture's offset has nothing on the Z axis.
         */
        private val FRONTIER_WOULD_LAND_AT = ChunkPos(519, 7)

        /** The two chunk folders this reaches into; a folder's own name is the label it is opened under. */
        private const val TERRAIN = "region"
        private const val ENTITIES = "entities"

        /** Where Primary's overworld is rebuilt inside the staging area. */
        private const val OVERWORLD_FOLDER = "overworld"

        /** log2 of the 32 chunks along a region file's edge. */
        private const val REGION_SHIFT = 5
    }

    @TempDir
    lateinit var dir: Path

    /** A second copy of the same save, for the run that has to agree with the first. */
    @TempDir
    lateinit var rehearsalDir: Path

    private lateinit var save: MergedDeploymentFixture

    @BeforeEach
    fun buildDeployment() {
        save = MergedDeploymentFixture(dir).build().withRealSecondaryChunks()
    }

    /** The whole merge, run for real against Secondary's real chunk data. */
    private fun merge(sample: Int = WorldMerge.DEFAULT_SAMPLE): MergeReport =
        WorldMerge(save.plan(planOnly = false).copy(sample = sample)).run()

    /**
     * The same merge, with [tool] doing the relocating — the seam
     * [WorldMergeTest] already reaches for when it needs the tool to misbehave.
     */
    private fun mergeWith(tool: McaSelector, sample: Int = WorldMerge.DEFAULT_SAMPLE): MergeReport =
        MergeStaging(
            save.plan(planOnly = false).copy(sample = sample),
            save.staging,
            save.levelDir,
            tool,
        ).write(WorldMerge(save.plan()).run().placement)

    // ---- what the operator sees ---------------------------------------------

    @Test
    fun `the report states how many chunks were compared and that they matched`() {
        val report = merge()

        assertEquals(
            listOf(
                "sample size              : 64 chunks from each relocated dimension",
                "chunks compared          : 5 — overworld 4 of 4, nether 1 of 1",
                "sampled diff             : every sampled chunk matched its source, block for block",
            ),
            report.sampled.lines(),
        )
    }

    @Test
    fun `the operator chooses how many chunks are sampled, and the choice is in the report`() {
        val report = merge(sample = 2)

        assertEquals(2, report.sampled.sampleSize)
        assertEquals(
            listOf(
                "sample size              : 2 chunks from each relocated dimension",
                "chunks compared          : 3 — overworld 2 of 4, nether 1 of 1",
                "sampled diff             : every sampled chunk matched its source, block for block",
            ),
            report.sampled.lines(),
        )
    }

    @Test
    fun `the sampled diff is a section of the merge report, after the relocation it checks`() {
        val report = merge()

        val lines = report.lines()
        assertTrue(
            lines.indexOf(report.relocation.lines().last()) < lines.indexOf(report.sampled.lines().first()),
            "the sampled diff should be reported after the relocation it checks: $lines",
        )
    }

    // ---- choosing the sample -------------------------------------------------

    @Test
    fun `the sample is drawn from across the whole footprint, not from one corner of it`() {
        // Secondary's overworld is two region files, and a sample of two that
        // took the first two chunks it found would never open the second file.
        val sampled = merge(sample = 2).sampled.dimension(DimensionRole.OVERWORLD).sampled

        assertEquals(listOf(FIRST, LAST), sampled)
        assertNotEquals(
            FIRST.x shr REGION_SHIFT,
            LAST.x shr REGION_SHIFT,
            "both sampled chunks came out of the same region file",
        )
    }

    @Test
    fun `a rehearsal and the real run compare the same chunks`() {
        val rehearsal = MergedDeploymentFixture(rehearsalDir).build().withRealSecondaryChunks()

        val night = merge(sample = 2)
        val before = WorldMerge(rehearsal.plan(planOnly = false).copy(sample = 2)).run()

        assertEquals(
            before.sampled.dimensions.map { it.role to it.sampled },
            night.sampled.dimensions.map { it.role to it.sampled },
        )
    }

    @Test
    fun `asking for no sample compares nothing, and the report says so rather than claiming a pass`() {
        val report = merge(sample = 0)

        assertEquals(0, report.sampled.compared)
        assertEquals(
            listOf(
                "sample size              : 0 chunks from each relocated dimension",
                "chunks compared          : none",
                "sampled diff             : nothing was compared, so nothing here says the terrain arrived",
            ),
            report.sampled.lines(),
        )
    }

    // ---- the failures nothing else can see ----------------------------------

    @Test
    fun `a relocated chunk whose entity data never arrived is caught`() {
        // The case the audit structurally cannot see, and the count cannot
        // either: entity data lives in a folder of its own, ChunkRelocation
        // counts the terrain folder, and data that is absent leaves behind no
        // stale coordinate for an audit to notice. A cow that never arrived is
        // simply not there, and only its source knows it should be.
        val failure = assertThrows(IllegalStateException::class.java) {
            mergeWith(losing(ENTITIES) { it.remove(FIRST_LANDS_AT) })
        }

        assertEquals(
            "the relocated copy of chunk 0, 0 of Secondary's overworld is not its source: its entities " +
                "differ: minecraft:cow at 8200.0, 64.0, 8.0 never arrived. The merge compares a sample " +
                "of the relocated chunks against the chunks they came from, because a chunk that never " +
                "arrived is invisible to everything that only reads the relocated data. Nothing has " +
                "been moved into place.",
            failure.message,
        )
    }

    @Test
    fun `the same merge commits when nothing is sampled, so it is the diff that catches it`() {
        // The other half of the test above: identical damage, identical merge,
        // and with the sampling turned off it goes straight into the live save.
        // Nothing else in the merge objects, which is the whole argument for
        // this phase existing.
        mergeWith(losing(ENTITIES) { it.remove(FIRST_LANDS_AT) }, sample = 0)

        assertTrue(Files.exists(save.mergeStamp), "the damaged merge did not commit")
        assertEquals(
            emptyMap<ChunkPos, CompoundTag>(),
            SyntheticChunks.read(
                save.primaryStorage(DimensionRole.OVERWORLD).resolve(ENTITIES),
                ENTITIES,
                save.primaryDimension(DimensionRole.OVERWORLD),
            ).filterKeys { it == FIRST_LANDS_AT },
        )
    }

    @Test
    fun `a relocated chunk that landed somewhere other than where it belongs is caught`() {
        // One chunk east of its destination. The terrain count still matches —
        // the same number of chunks arrived — and every coordinate in the
        // relocated data is a Primary one, so nothing that reads only the
        // relocated save has anything to object to.
        val failure = assertThrows(IllegalStateException::class.java) {
            mergeWith(
                losing(TERRAIN) {
                    it[ChunkPos(FIRST_LANDS_AT.x + 1, FIRST_LANDS_AT.z)] = it.getValue(FIRST_LANDS_AT)
                    it.remove(FIRST_LANDS_AT)
                },
            )
        }

        assertTrue(
            failure.message!!.contains(
                "the relocated copy of chunk 0, 0 of Secondary's overworld is not its source: it never " +
                    "arrived: nothing at all is stored at chunk 512, 0, where the relocation was to put it",
            ),
            failure.message,
        )
    }

    @Test
    fun `a relocated chunk whose blocks were changed is caught`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            mergeWith(losing(TERRAIN) { it[FIRST_LANDS_AT] = madeOf(it.getValue(FIRST_LANDS_AT), "minecraft:dirt") })
        }

        assertTrue(
            failure.message!!.contains(
                "its blocks differ: section y -4 is not the section the source has there",
            ),
            failure.message,
        )
    }

    @Test
    fun `a relocated chunk whose block entity never arrived is caught`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            mergeWith(losing(TERRAIN) { it[FIRST_LANDS_AT] = withoutBlockEntities(it.getValue(FIRST_LANDS_AT)) })
        }

        assertTrue(
            failure.message!!.contains(
                "its block entities differ: minecraft:chest at 8192, 64, 0 never arrived",
            ),
            failure.message,
        )
    }

    @Test
    fun `a frontier chunk that travelled after all is caught`() {
        // The frontier is meant to stay behind and regenerate from Primary's
        // seed (merge spec, User Story 14), so for those chunks the evidence
        // wanted is the opposite one. Swapping it for the last relocated chunk
        // keeps the terrain count exactly where ChunkRelocation expects it.
        val failure = assertThrows(IllegalStateException::class.java) {
            mergeWith(
                losing(TERRAIN) {
                    it[FRONTIER_WOULD_LAND_AT] = it.getValue(LAST_LANDS_AT)
                    it.remove(LAST_LANDS_AT)
                },
            )
        }

        assertTrue(
            failure.message!!.contains(
                "the relocated copy of chunk 7, 7 of Secondary's overworld is not its source: vanilla " +
                    "never finished it — its status is \"minecraft:noise\" — so it should have stayed " +
                    "at Secondary's frontier, and instead a copy of it arrived at chunk 519, 7",
            ),
            failure.message,
        )
    }

    // ---- what a mismatch costs ----------------------------------------------

    @Test
    fun `a mismatch leaves the live save exactly as it was and clears the staging directory`() {
        val before = save.contents()

        assertThrows(IllegalStateException::class.java) {
            mergeWith(losing(ENTITIES) { it.remove(FIRST_LANDS_AT) })
        }

        assertEquals(before, save.contents())
        assertFalse(Files.exists(save.staging))
        assertFalse(Files.exists(save.mergeStamp))
    }

    // ---- making the relocation go wrong -------------------------------------

    /**
     * The real tool, with [change] applied to what it just wrote.
     *
     * The merge reaches its relocation through one narrow seam precisely so the
     * phases after it can be proved against a tool that got something wrong
     * (merge spec, "Relocation") — and a tool that got something wrong is not
     * something a test can wait for. Only Secondary's overworld is touched: the
     * nether would be a second copy of the same assertion.
     */
    private fun losing(
        folder: String,
        change: (MutableMap<ChunkPos, CompoundTag>) -> Unit,
    ): McaSelector = object : McaSelector(Path.of(System.getProperty(McaSelector.JAR_PROPERTY))) {
        override fun relocate(
            from: Path,
            into: Path,
            selection: Path,
            chunksX: Int,
            chunksZ: Int,
        ): String = super.relocate(from, into, selection, chunksX, chunksZ).also {
            if (into.name == OVERWORLD_FOLDER) rewrite(into.resolve(folder), folder, change)
        }
    }

    /** [folder]'s relocated chunk data, read back, changed and written over the top of itself. */
    private fun rewrite(
        folder: Path,
        type: String,
        change: (MutableMap<ChunkPos, CompoundTag>) -> Unit,
    ) {
        val dimension = save.primaryDimension(DimensionRole.OVERWORLD)
        val chunks = LinkedHashMap(SyntheticChunks.read(folder, type, dimension))
        change(chunks)
        Files.newDirectoryStream(folder, "r.*.mca").use { it.toList() }.forEach(Files::delete)
        val byFile = chunks.entries.groupBy {
            RegionFilePos(it.key.x shr REGION_SHIFT, it.key.z shr REGION_SHIFT)
        }
        for ((at, inFile) in byFile) {
            write(folder, at, dimension, type, inFile.associate { it.key to it.value })
        }
    }

    private fun write(
        folder: Path,
        at: RegionFilePos,
        dimension: ResourceKey<Level>,
        type: String,
        chunks: Map<ChunkPos, CompoundTag>,
    ) {
        RegionFile(RegionStorageInfo("world", dimension, type), folder.resolve(at.fileName), folder, false)
            .use { region ->
                for ((position, tag) in chunks) {
                    region.getChunkDataOutputStream(position).use { NbtIo.write(tag, it) }
                }
            }
    }

    /** The same chunk with its one section built out of [block] instead of stone. */
    private fun madeOf(chunk: CompoundTag, block: String): CompoundTag = chunk.copy().also {
        it.getListOrEmpty("sections")
            .getCompoundOrEmpty(0)
            .getCompoundOrEmpty("block_states")
            .getListOrEmpty("palette")
            .getCompoundOrEmpty(0)
            .putString("Name", block)
    }

    /** The same chunk with the chest that stood in it gone. */
    private fun withoutBlockEntities(chunk: CompoundTag): CompoundTag =
        chunk.copy().also { it.remove("block_entities") }

}
