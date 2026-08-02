package eu.mctraveler.importer

import eu.mctraveler.importer.CoordinateBearingChunks.Block
import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.ChunkPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The audit of the relocated chunks, driven through the merge command end to end
 * against the same synthetic run directory the other merge suites plan against
 * (merge spec, "Testing Decisions"; ticket 03). A sibling of [WorldMergeTest]
 * rather than more of it, because the fixture it needs — a chunk carrying one of
 * every coordinate-bearing thing — is worth reading on its own.
 *
 * Every test relocates for real. MCA Selector is resolved and checksum-verified by
 * the build and nothing here stubs it, so what the audit walks is what the tool
 * actually produced rather than what the merge believes it produced — which is the
 * only way this phase can be evidence of anything.
 *
 * The offset is passed explicitly and has a different value on each axis, a
 * negative Z and a nether eighth that is neither: an audit that lost the Z shift
 * or swapped the axes would pass against the searched offset for this fixture,
 * which happens to be `x +8192, z +0`. Every number below is worked out by hand
 * from `x +8192, z -4096`, so Secondary's overworld chunk (5, 3) lands at
 * (517, −253) and its blocks x 80…95 z 48…63 land at x 8272…8287 z −4048…−4033.
 */
class WorldMergeAuditTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        private val OFFSET = MergeOffset(8192, -4096)

        /**
         * Every chunk the merge relocates in this fixture, across all three of a
         * dimension's folders: Secondary's three finished overworld chunks and its
         * one nether chunk, times terrain, entities and points of interest. The
         * frontier chunk is not among them, because it never travelled.
         */
        private const val CHUNKS_RELOCATED = 12

        /**
         * How many coordinates those chunks carry between them. Asserted exactly
         * rather than loosely, because "the audit walked every relocated chunk" is
         * the first thing this phase claims and a silent drop to nearly-all of them
         * is precisely the regression that would otherwise go unnoticed.
         */
        private const val COORDINATES_IN_THE_FIXTURE = 37
    }

    @TempDir
    lateinit var dir: Path

    private lateinit var save: MergedDeploymentFixture

    // Instance-level, not companion: touching a Minecraft class from a companion
    // initialiser would run it before @BeforeAll has bootstrapped the game.

    private val overworld = DimensionRole.OVERWORLD

    /** Where [CoordinateBearingChunks.AT] lands under [OFFSET]. */
    private val landsAt = ChunkPos(517, -253)

    @BeforeEach
    fun buildDeployment() {
        save = MergedDeploymentFixture(dir).build().withRealSecondaryChunks()
        CoordinateBearingChunks.writeInto(save.levelDir, save.secondaryDimension(overworld))
    }

    private fun merge(): MergeReport = WorldMerge(save.plan(offset = OFFSET, planOnly = false)).run()

    private fun audit(): ChunkAuditReport = merge().section<ChunkAuditReport>()

    // ---- a chunk that arrived whole -----------------------------------------

    @Test
    fun `a chunk holding one of every coordinate-bearing thing passes the audit`() {
        // The villager's memories are written where a correct relocation would
        // have left them; that the relocation does not is the subject of the tests
        // further down, and this one is about the audit not inventing a leftover.
        villagerRemembering(merged(CoordinateBearingChunks.BED))

        val report = audit()

        assertEquals(CHUNKS_RELOCATED, report.chunksAudited)
        assertEquals(COORDINATES_IN_THE_FIXTURE, report.coordinatesChecked)
    }

    @Test
    fun `a velocity and a uuid are not mistaken for the places they would sit inside`() {
        // The cow's velocity is (0.1, 0.0, -0.2) and its uuid is (1, 2, 3, 4);
        // read as coordinates both fall inside Secondary's old footprint, so an
        // audit that took every triple for a position would refuse this merge
        // every time. Passing at all is half the claim; the other half is that
        // neither was quietly rewritten either.
        villagerRemembering(merged(CoordinateBearingChunks.BED))

        merge()

        val cow = entityIn(relocated("entities", "entities"), "minecraft:cow")
        assertEquals(listOf(0.1, 0.0, -0.2), cow.getListOrEmpty("Motion").doubles())
        assertEquals(listOf(1, 2, 3, 4), cow.getIntArray("UUID").orElseThrow().toList())
    }

    // ---- the cosmetic tier: repaired -----------------------------------------

    @Test
    fun `a lodestone compass is retargeted however deeply it is buried`() {
        villagerRemembering(merged(CoordinateBearingChunks.BED))

        val report = audit()

        // MCA Selector cannot do this one: the target names the dimension it is
        // in, and that `mctraveler:secondary` becomes `minecraft:overworld` is
        // this merge's own knowledge.
        val chest = blockEntityIn(relocated("region", "chunk"), "minecraft:chest")
        val items = chest.getListOrEmpty("Items")
        assertEquals(
            merged(CoordinateBearingChunks.COMPASS_TARGET).toList(),
            targetOf(items.getCompoundOrEmpty(0)),
        )
        // A chest inside a shulker box inside the chest: reached by the same rule
        // as the one lying beside it.
        assertEquals(
            merged(CoordinateBearingChunks.NESTED_COMPASS_TARGET).toList(),
            targetOf(itemIn(itemIn(items.getCompoundOrEmpty(1)))),
        )
        // And one that is not in a container at all, but in an item frame.
        assertEquals(
            merged(CoordinateBearingChunks.FRAMED_COMPASS_TARGET).toList(),
            targetOf(entityIn(relocated("entities", "entities"), "minecraft:item_frame").getCompoundOrEmpty("Item")),
        )
        assertEquals(3, report.retargeted)
    }

    @Test
    fun `a retargeted compass names Primary's dimension, not the one being retired`() {
        villagerRemembering(merged(CoordinateBearingChunks.BED))

        merge()

        val target = blockEntityIn(relocated("region", "chunk"), "minecraft:chest")
            .getListOrEmpty("Items")
            .getCompoundOrEmpty(0)
            .let(::trackerIn)
        assertEquals("minecraft:overworld", target.getStringOr("dimension", ""))
    }

    @Test
    fun `a compass pointing into Secondary's End is named rather than guessed at`() {
        villagerRemembering(merged(CoordinateBearingChunks.BED))
        putInTheChest(CoordinateBearingChunks.compass(Block(1, 50, 2), "mctraveler:secondary_end"))

        val report = audit()

        assertEquals(3, report.retargeted)
        assertEquals(
            listOf(
                "a lodestone compass in overworld at chunk 517, -253 still points into Secondary's End, " +
                    "which the merge discards",
            ),
            report.unrepairable,
        )
    }

    // ---- the cosmetic tier: reported ----------------------------------------

    @Test
    fun `a command block naming literal coordinates is listed and never rewritten`() {
        villagerRemembering(merged(CoordinateBearingChunks.BED))

        val report = audit()

        val at = merged(CoordinateBearingChunks.COMMAND_BLOCK)
        assertEquals(
            listOf(
                LiteralCoordinates(overworld, at.x, at.y, at.z, CoordinateBearingChunks.COMMAND),
            ),
            report.commandBlocks,
        )
        // A command is a program, and the numbers in one can be a place, a count
        // or a tick. It comes out of the merge byte for byte.
        assertEquals(
            CoordinateBearingChunks.COMMAND,
            blockEntityIn(relocated("region", "chunk"), "minecraft:command_block").getStringOr("Command", ""),
        )
    }

    @Test
    fun `a command that describes a place relative to itself is not reported`() {
        assertTrue(ChunkAudit.namesLiteralCoordinates("/tp @p 85 64 53"))
        assertTrue(ChunkAudit.namesLiteralCoordinates("/setblock 100 64 -200 minecraft:stone"))
        assertTrue(ChunkAudit.namesLiteralCoordinates("execute positioned 1 2 3 run say hi"))
        // Relative and local coordinates travel with the block that runs them.
        assertFalse(ChunkAudit.namesLiteralCoordinates("/setblock ~ ~-1 ~ minecraft:stone"))
        assertFalse(ChunkAudit.namesLiteralCoordinates("/tp @p ^ ^ ^5"))
        assertFalse(ChunkAudit.namesLiteralCoordinates("/give @p minecraft:diamond 64"))
        assertFalse(ChunkAudit.namesLiteralCoordinates("/say the walls are 3 blocks high"))
    }

    // ---- the structural tier: the merge stops -------------------------------

    @Test
    fun `one structural coordinate left behind fails the whole merge and writes nothing`() {
        // A painting's tile position as 26.2 spells it. MCA Selector 2.8 still
        // moves the pre-1.21.5 `TileX`/`TileY`/`TileZ` and knows nothing of
        // `block_pos`, so this arrives in Primary still naming a block in
        // Secondary — the painting would hang on nothing.
        CoordinateBearingChunks.edit(save.levelDir, save.secondaryDimension(overworld), "entities", "entities") {
            entityIn(it, "minecraft:painting")
                .putIntArray("block_pos", CoordinateBearingChunks.PAINTING.toList().toIntArray())
        }
        val before = save.contents()

        val refusal = assertThrows(MigrationRefused::class.java) { merge() }

        assertTrue(
            refusal.message!!.startsWith(
                "the relocated chunks still hold 1 coordinate pointing into the place Secondary used to be",
            ),
            refusal.message,
        )
        assertTrue(
            refusal.message!!.contains(
                "overworld: in chunk 517, -253: minecraft:painting.block_pos still names 91, 64, 59",
            ),
            refusal.message,
        )
        // The refusal has to be actionable, so it says where Secondary was and
        // how far the merge was moving it.
        assertTrue(
            refusal.message!!.contains(
                "Secondary's overworld used to cover x 0…1023  z 0…511 and moved x +8192, z -4096",
            ),
            refusal.message,
        )
        assertEquals(before, save.contents())
        assertFalse(Files.exists(save.staging))
    }

    @Test
    fun `MCA Selector 2_8 leaves 26_2's inline block positions behind, and this is what catches it`() {
        // 1.21.5's InlineBlockPosFormatFix renamed `Leash` and the tile positions
        // to `leash` and `block_pos`; MCA Selector 2.8 still relocates only the
        // old spellings. Every one of these would arrive pointing at a block in
        // Secondary — a leash tied to nothing, a frame and a painting hung on air.
        CoordinateBearingChunks.addTheInlineBlockPositions(save.levelDir, save.secondaryDimension(overworld))

        val refusal = assertThrows(MigrationRefused::class.java) { merge() }

        assertTrue(refusal.message!!.contains("still hold 3 coordinates"), refusal.message)
        for (leftover in listOf(
            "minecraft:cow.leash still names 86, 64, 54",
            "minecraft:item_frame.block_pos still names 90, 64, 58",
            "minecraft:painting.block_pos still names 91, 64, 59",
        )) {
            assertTrue(refusal.message!!.contains(leftover), refusal.message)
        }
        assertFalse(Files.exists(save.mergeStamp))
    }

    @Test
    fun `MCA Selector 2_8 leaves a villager's memories behind, and this is what catches it`() {
        // A memory is written through `ExpirableValue`, which wraps it in `value`;
        // MCA Selector reads `pos` off the memory itself, one level too shallow.
        // So a villager arrives still remembering Secondary's coordinates.
        villagerRemembering(
            CoordinateBearingChunks.BED,
            dimension = CoordinateBearingChunks.SECONDARY_OVERWORLD,
        )

        val refusal = assertThrows(MigrationRefused::class.java) { merge() }

        assertTrue(
            refusal.message!!.contains(
                "minecraft:villager.Brain.memories.minecraft:home.value.pos still names 80, 64, 48",
            ),
            refusal.message,
        )
    }

    // ---- the cross-check across two files -----------------------------------

    @Test
    fun `a villager remembering a place no point of interest claims fails the merge`() {
        // Everything about this villager moved exactly as it should have, and the
        // trading hall is dead anyway: nothing claims the workstation it walks to.
        // That is the invariant no single chunk can establish, which is why it is
        // asked once the whole dimension has been read.
        villagerRemembering(
            home = merged(CoordinateBearingChunks.BED),
            jobSite = Block(9000, 64, -4000),
            meetingPoint = merged(CoordinateBearingChunks.MEETING_POINT),
        )

        val refusal = assertThrows(MigrationRefused::class.java) { merge() }

        assertTrue(
            refusal.message!!.startsWith("the relocation left 1 villager memory without the point-of-interest"),
            refusal.message,
        )
        assertTrue(
            refusal.message!!.contains(
                "the minecraft:villager in overworld chunk 517, -253 remembers its minecraft:job_site at " +
                    "9000, 64, -4000, and no point-of-interest record arrived there",
            ),
            refusal.message,
        )
        assertEquals("{\n  \"regions\": {}\n}\n", save.regionsJson())
    }

    // ---- the report ----------------------------------------------------------

    @Test
    fun `the report separates what the merge repaired from what needs an operator`() {
        villagerRemembering(merged(CoordinateBearingChunks.BED))

        val report = audit()

        assertEquals(
            listOf(
                "chunks audited           : $CHUNKS_RELOCATED",
                "coordinates checked      : $COORDINATES_IN_THE_FIXTURE",
                "repaired automatically   : 3 lodestone compass targets",
                "needs an operator        : 1, listed below and never rewritten",
                "  command block          : overworld 8275, 64, -4045 — ${CoordinateBearingChunks.COMMAND}",
            ),
            report.lines(),
        )
    }

    @Test
    fun `a merge with nothing to repair says so rather than reporting a zero`() {
        // The save the other merge suites use: real chunks, but none of them
        // carrying a compass or a command block.
        save = MergedDeploymentFixture(dir).build().withRealSecondaryChunks()

        val report = audit()

        assertEquals(
            listOf(
                "chunks audited           : $CHUNKS_RELOCATED",
                "coordinates checked      : 20",
                "repaired automatically   : nothing needed it",
                "needs an operator        : nothing",
            ),
            report.lines(),
        )
    }

    @Test
    fun `the audit's section sits with the others, behind the placement`() {
        villagerRemembering(merged(CoordinateBearingChunks.BED))

        val report = merge()

        assertEquals("offset                   : x +8192, z -4096  (nether x +1024, z -512)", report.lines()[0])
        assertTrue(report.lines().contains("chunks audited           : $CHUNKS_RELOCATED"), "${report.lines()}")
    }

    // ---- reading the merged save back ---------------------------------------

    private fun merged(at: Block): Block = at.merged(OFFSET, overworld)

    private fun villagerRemembering(
        home: Block,
        jobSite: Block = home,
        meetingPoint: Block = home,
        dimension: String = "minecraft:overworld",
    ) = CoordinateBearingChunks.addVillager(
        save.levelDir,
        save.secondaryDimension(overworld),
        home = home,
        jobSite = jobSite,
        meetingPoint = meetingPoint,
        remembers = dimension,
    )

    /** [item] added to the chest in Secondary's rich chunk, before the merge runs. */
    private fun putInTheChest(item: CompoundTag) =
        CoordinateBearingChunks.edit(save.levelDir, save.secondaryDimension(overworld), "region", "chunk") {
            blockEntityIn(it, "minecraft:chest").getListOrEmpty("Items").add(item)
        }

    /** The rich chunk as it stands in Primary after the merge. */
    private fun relocated(folder: String, type: String): CompoundTag = SyntheticChunks
        .read(save.primaryStorage(overworld).resolve(folder), type, save.primaryDimension(overworld))
        .getValue(landsAt)

    private fun blockEntityIn(chunk: CompoundTag, id: String): CompoundTag =
        chunk.getListOrEmpty("block_entities").first(id)

    private fun entityIn(chunk: CompoundTag, id: String): CompoundTag =
        chunk.getListOrEmpty("Entities").first(id)

    private fun ListTag.first(id: String): CompoundTag = (0 until size)
        .map { getCompoundOrEmpty(it) }
        .first { it.getStringOr("id", "") == id }

    private fun itemIn(container: CompoundTag): CompoundTag =
        container.getCompoundOrEmpty("components")
            .getListOrEmpty("minecraft:container")
            .getCompoundOrEmpty(0)
            .getCompoundOrEmpty("item")

    private fun trackerIn(compass: CompoundTag): CompoundTag =
        compass.getCompoundOrEmpty("components")
            .getCompoundOrEmpty("minecraft:lodestone_tracker")
            .getCompoundOrEmpty("target")

    private fun targetOf(compass: CompoundTag): List<Int> =
        trackerIn(compass).getIntArray("pos").orElseThrow().toList()

    private fun ListTag.doubles(): List<Double> = (0 until size).map { getDoubleOr(it, Double.NaN) }
}
