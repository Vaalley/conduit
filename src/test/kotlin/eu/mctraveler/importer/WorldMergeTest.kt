package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
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
    }

    @TempDir
    lateinit var dir: Path

    private lateinit var save: MergedDeploymentFixture

    @BeforeEach
    fun buildDeployment() {
        save = MergedDeploymentFixture(dir).build()
    }

    private fun merge(
        clearance: Int = WorldMerge.DEFAULT_CLEARANCE,
        offset: MergeOffset? = null,
        searchLimit: Int = WorldMerge.DEFAULT_SEARCH_LIMIT,
    ) = WorldMerge(save.plan(clearance, offset, searchLimit)).run()

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

        assertEquals(
            "no 4096-aligned slot within 8192 blocks of the origin clears 100000 nether blocks of " +
                "Primary's chunk data — 24 slots tried, and Primary's overworld reaches " +
                "x -512…511  z -512…511; Primary's nether reaches x 0…511  z 0…511. " +
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
}
