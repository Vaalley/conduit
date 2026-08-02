package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Path
import java.util.UUID
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.ChunkPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Only the part of Secondary anybody was ever meant to reach comes across (merge
 * spec, "What comes across"; ticket 13).
 *
 * A sibling of [WorldMergeTest] rather than more of it: the seam is the same one
 * — [WorldMerge.run] end to end over a [MergedDeploymentFixture], with the real
 * relocation tool doing the real relocation — but everything here turns on
 * coordinates near a border fifty thousand blocks out, so the fixture's chunks
 * are placed for this suite rather than shared with it.
 *
 * Every number below is arrived at by hand from three facts, so a test that goes
 * red says the arithmetic changed rather than that a golden file drifted:
 *
 * - the border is ±50,000 blocks and the bleed 512, so the clip carries region
 *   files −98 through 97 — the last file whose far edge, at 50,175, has not
 *   passed 50,512;
 * - a merge here moves the overworld `x +8192, z −4096`, which is 512 chunks east
 *   and 256 north, and the nether an eighth of that: 64 chunks east and 32 north
 *   (see [OFFSET] for why it is stated rather than searched for);
 * - a region file spans 32 chunks and 512 blocks, so chunk 3126 is in file 97 and
 *   chunk 3136 is the first chunk of file 98.
 */
class WorldMergeBorderClipTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        /** Chunk 0: the ordinary middle of Secondary, a long way inside the border. */
        private val AT_HOME = SyntheticChunks.Chunk(0, 0)

        /**
         * Block x 50,016 — just past the border, and inside the bleed. In region
         * file 97, which is the last file the default clip carries.
         */
        private val IN_THE_BLEED = SyntheticChunks.Chunk(3126, 0)

        /**
         * Block x 1,000,000, in region file 1953. Somebody's admin excursion:
         * worth nothing, and a million blocks of slot to the placement search.
         */
        private val FAR_OUTSIDE = SyntheticChunks.Chunk(62500, 0)

        /** Nether block x 40,000, inside the border — and outside an eighth of it. */
        private val NETHER_INSIDE = SyntheticChunks.Chunk(2500, 0)

        /** Nether block x 60,000, outside the border — and inside eight times it. */
        private val NETHER_OUTSIDE = SyntheticChunks.Chunk(3750, 0)

        private val ALICE = UUID.fromString("11111111-2222-4333-8444-555555555555")

        /**
         * The offset every test that actually relocates passes, rather than
         * letting the search answer.
         *
         * Two reasons, and both are about this suite's geography rather than
         * about the clip. A Secondary clipped to ±50,000 spans a hundred region
         * files, so the slot Primary's tiny fixture footprint alone would give up
         * — `x +8192, z +0` — sets the landmass down *inside the box Secondary
         * used to occupy*, and the audit would read every arriving coordinate as
         * one that had never left. Ticket 18 taught the search to refuse that
         * slot, so it now answers `x +0, z +8192` here rather than needing to be
         * told; this offset is still stated because a zero Z would let a clip
         * that lost the Z axis, flipped its sign or swapped the two pass every
         * assertion below. So: 16 region files east and 8 north in the overworld,
         * an eighth of each in the nether, checked by exactly the test a searched
         * offset passes — including, now, that it clears Secondary's own ground.
         */
        private val OFFSET = MergeOffset(8192, -4096)
    }

    @TempDir
    lateinit var dir: Path

    private lateinit var save: MergedDeploymentFixture

    @BeforeEach
    fun buildDeployment() {
        save = MergedDeploymentFixture(dir).build()
    }

    /**
     * Secondary with one chunk at home, one in the bleed and one a million blocks
     * out, and a nether carrying the same argument in its own coordinates.
     */
    private fun straddlingTheBorder() = save.withRealSecondaryChunks(
        overworld = listOf(AT_HOME, IN_THE_BLEED, FAR_OUTSIDE),
        nether = listOf(AT_HOME, NETHER_INSIDE, NETHER_OUTSIDE),
    )

    /** The whole merge, run for real; see [OFFSET] for why the offset is stated. */
    private fun merge(border: SecondaryBorder = SecondaryBorder()) =
        WorldMerge(save.plan(offset = OFFSET, planOnly = false, border = border)).run()

    /** Where Secondary would go, and what of it would come. Writes nothing, and lets the search answer. */
    private fun plan(border: SecondaryBorder = SecondaryBorder()) =
        WorldMerge(save.plan(border = border)).run()

    private fun relocatedChunks(role: DimensionRole): Set<ChunkPos> = SyntheticChunks.positions(
        save.primaryStorage(role).resolve("region"),
        "chunk",
        save.primaryDimension(role),
    )

    // ---- what the placement search is asked to make room for ----------------

    @Test
    fun `a chunk far outside the border is not measured into the footprint`() {
        straddlingTheBorder()

        val overworld = plan().placement.dimension(DimensionRole.OVERWORLD)

        // Unclipped this would be x 0…1000447 across 1954 region files, and the
        // search would be hunting for a slot a million blocks wide to put three
        // chunks in.
        assertEquals("x 0…50175  z 0…511", overworld.secondary.describeBlocks())
        assertEquals(98, overworld.secondary.fileCount)
    }

    @Test
    fun `the border and the bleed are echoed in the plan, so a rehearsal and the real run compare`() {
        straddlingTheBorder()

        val lines = plan().lines()

        assertTrue(
            lines.contains("Secondary's border       : ±50000 blocks, with 512 of bleed carried past it"),
            "$lines",
        )
        assertTrue(
            lines.contains(
                "left outside the border  : 2 region files, the furthest reaching 950447 blocks past it",
            ),
            "$lines",
        )
    }

    @Test
    fun `a plan states the clip without writing anything, so the numbers can be argued with first`() {
        straddlingTheBorder()
        val before = save.contents()

        val report = plan()

        assertEquals(before, save.contents())
        assertEquals(2, report.clip.filesLeftOutside)
        assertEquals(950447, report.clip.furthestBeyond)
    }

    @Test
    fun `the clip leads the sections, because it is what constrained every phase after it`() {
        straddlingTheBorder()

        val report = merge()
        val lines = report.lines()

        // The placement first, because it is what the operator has to accept;
        // then what of Secondary is coming at all; then what each phase did with
        // it. Asserted as one run of lines rather than by searching for them,
        // because a section's first line is not always unique in the report.
        val opening = report.placement.lines() + report.clip.lines()
        assertEquals(opening, lines.take(opening.size))
        assertEquals(report.relocation.lines(), lines.drop(opening.size).take(report.relocation.lines().size))
    }

    // ---- what actually moves ------------------------------------------------

    @Test
    fun `a chunk far outside the border is not relocated, and the ones inside it are`() {
        straddlingTheBorder()

        merge()

        assertEquals(
            setOf(ChunkPos(512, -256), ChunkPos(3638, -256)),
            relocatedChunks(DimensionRole.OVERWORLD),
        )
    }

    @Test
    fun `a chunk left outside the border is left where it is, not deleted`() {
        straddlingTheBorder()

        merge()

        assertEquals(
            setOf(ChunkPos(0, 0), ChunkPos(3126, 0), ChunkPos(62500, 0)),
            SyntheticChunks.positions(
                Footprint.storageFolder(save.levelDir, save.secondaryDimension(DimensionRole.OVERWORLD))
                    .resolve("region"),
                "chunk",
                save.secondaryDimension(DimensionRole.OVERWORLD),
            ),
        )
    }

    @Test
    fun `a chunk in the bleed, just outside the border, comes across`() {
        straddlingTheBorder()

        merge()

        // Block 50,016 is past the border and 496 blocks inside the bleed.
        assertTrue(ChunkPos(3638, -256) in relocatedChunks(DimensionRole.OVERWORLD))
    }

    @Test
    fun `without the bleed that same chunk stays behind, so the bleed is what carries it`() {
        straddlingTheBorder()

        merge(SecondaryBorder(bleed = 0))

        assertEquals(setOf(ChunkPos(512, -256)), relocatedChunks(DimensionRole.OVERWORLD))
    }

    @Test
    fun `the border applies at the same coordinates in the nether, not at one eighth of them`() {
        straddlingTheBorder()

        merge()

        // 40,000 nether blocks is inside a border that is not divided by eight,
        // and 60,000 is outside one that is not multiplied by it. A ÷8 reading
        // would keep neither; a ×8 reading would keep both.
        assertEquals(
            setOf(ChunkPos(64, -32), ChunkPos(2564, -32)),
            relocatedChunks(DimensionRole.NETHER),
        )
    }

    @Test
    fun `the clip carries whole region files, so no chunk is split from the file it lives in`() {
        // File 97 holds one chunk inside the border and one in the bleed; file 98
        // holds one chunk inside the bleed and one past it. Whole files travel, so
        // 97 comes entire and 98 stays entire.
        save.withRealSecondaryChunks(
            overworld = listOf(
                AT_HOME,
                SyntheticChunks.Chunk(3104, 0),
                IN_THE_BLEED,
                SyntheticChunks.Chunk(3136, 0),
                SyntheticChunks.Chunk(3167, 0),
            ),
        )

        merge()

        assertEquals(
            setOf(ChunkPos(512, -256), ChunkPos(3616, -256), ChunkPos(3638, -256)),
            relocatedChunks(DimensionRole.OVERWORLD),
        )
    }

    @Test
    fun `a Secondary with nothing near its border is carried exactly as it is without a clip`() {
        save.withRealSecondaryChunks()

        val report = merge()

        assertEquals(
            setOf(ChunkPos(512, -256), ChunkPos(517, -253), ChunkPos(544, -256)),
            relocatedChunks(DimensionRole.OVERWORLD),
        )
        assertEquals(0, report.relocation.outsideBorder)
        assertEquals(
            listOf(
                "Secondary's border       : ±50000 blocks, with 512 of bleed carried past it",
                "left outside the border  : nothing — every region file of Secondary is inside it",
            ),
            report.clip.lines(),
        )
    }

    // ---- what the report says -----------------------------------------------

    @Test
    fun `the report counts the chunks left outside the border, apart from the frontier`() {
        straddlingTheBorder()

        val report = merge()

        val overworld = report.relocation.dimension(DimensionRole.OVERWORLD)
        assertEquals(2, overworld.relocated)
        assertEquals(1, overworld.outsideBorder)
        // The frontier count means what it always meant: chunks vanilla never
        // finished, and this fixture has none.
        assertEquals(0, overworld.dropped)
        assertEquals(1, report.relocation.dimension(DimensionRole.NETHER).outsideBorder)
        assertTrue(
            report.relocation.lines()
                .contains("  chunks outside border  : 1 (past Secondary's world border)"),
            "${report.relocation.lines()}",
        )
    }

    @Test
    fun `the report says how far out the furthest thing left behind was`() {
        straddlingTheBorder()

        val report = merge()

        // Region file 1953 reaches block 1,000,447, which is 950,447 past the
        // border — a stray teleport rather than anybody's base.
        assertEquals(950447, report.clip.furthestBeyond)
        assertEquals(
            mapOf(
                DimensionRole.OVERWORLD to listOf(RegionFilePos(1953, 0)),
                DimensionRole.NETHER to listOf(RegionFilePos(117, 0)),
            ),
            report.clip.leftOutside,
        )
    }

    // ---- what is counted rather than gated on -------------------------------

    @Test
    fun `a Region anchored outside the border is swept like any other, and counted`() {
        straddlingTheBorder()
        save.withRegions(
            """
            {
              "regions": {
                "0": {
                  "title": "Outpost",
                  "start-x": 900000,
                  "start-z": 0,
                  "end-x": 900100,
                  "end-z": 100,
                  "world": "last",
                  "members": [
                    "11111111-1111-1111-1111-111111111111"
                  ]
                },
                "1": {
                  "title": "Harbour",
                  "start-x": 100,
                  "start-z": 200,
                  "end-x": 300,
                  "end-z": 400,
                  "world": "last",
                  "members": [
                    "22222222-2222-2222-2222-222222222222"
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val report = merge()

        // Swept exactly like the one inside: moved onto Primary at the offset,
        // never refused over. Its chunks are the thing that stayed behind.
        assertEquals(2, report.regions.movedCount)
        assertEquals(1, report.regions.regionsOutsideBorder)
        assertTrue(
            report.regions.lines().contains(
                "Regions outside border   : 1 — swept anyway; the chunks under them stayed in Secondary",
            ),
            "${report.regions.lines()}",
        )
        assertTrue(save.regionsJson().contains("\"start-x\": 908192"), save.regionsJson())
        assertTrue(save.regionsJson().contains("\"start-z\": -4096"), save.regionsJson())
    }

    @Test
    fun `a player anchored outside the border is swept like any other, and counted`() {
        straddlingTheBorder()
        save.playerSave(ALICE, standingAt("mctraveler:secondary", x = 900000.5, z = 0.5))

        val report = merge()

        assertEquals(listOf(ALICE), report.players.anchoredOutsideBorder)
        // Moved anyway — the merge does not gate on this, it records it.
        assertEquals(
            listOf(908192.5, 64.0, -4095.5),
            save.savedPlayer(ALICE).getListOrEmpty("Pos").let { pos ->
                (0 until pos.size).map { pos.getDoubleOr(it, Double.NaN) }
            },
        )
        assertTrue(
            report.players.lines().contains(
                "players outside border   : 1 — swept anyway; the chunks under them stayed in Secondary",
            ),
            "${report.players.lines()}",
        )
    }

    @Test
    fun `a player well inside the border is not counted against it`() {
        straddlingTheBorder()
        save.playerSave(ALICE, standingAt("mctraveler:secondary", x = 100.5, z = -200.25))

        val report = merge()

        assertEquals(emptyList<UUID>(), report.players.anchoredOutsideBorder)
        assertFalse(report.players.lines().any { it.startsWith("players outside border") })
    }

    // ---- the refusal --------------------------------------------------------

    @Test
    fun `a border that carries none of Secondary refuses rather than merging an empty map`() {
        save.withRealSecondaryChunks(
            overworld = listOf(FAR_OUTSIDE),
            nether = listOf(FAR_OUTSIDE),
        )

        val refusal = assertThrows<MigrationRefused> { plan() }

        assertTrue(refusal.message!!.contains("carries none of Secondary's chunk data"), "$refusal")
        assertTrue(refusal.message!!.contains("--border and --bleed"), "$refusal")
    }

    @Test
    fun `a border smaller than a region file is not a border the clip can work in`() {
        assertThrows<IllegalArgumentException> { SecondaryBorder(halfExtent = 100) }
    }

    /** A save that puts its owner at [x], [z] in [dimension], and records nothing else of interest. */
    private fun standingAt(dimension: String, x: Double, z: Double) = CompoundTag().apply {
        putString("Dimension", dimension)
        put(
            "Pos",
            ListTag().apply {
                add(DoubleTag.valueOf(x))
                add(DoubleTag.valueOf(64.0))
                add(DoubleTag.valueOf(z))
            },
        )
        putInt("DataVersion", SyntheticChunks.dataVersion)
    }
}
