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
import org.junit.jupiter.api.Assertions.assertNotEquals
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
        // Everything in the fixture is written in Secondary's own coordinates and
        // relocated for real, so a leftover here would be one the tool actually
        // left. This is ticket 03's last acceptance criterion, which could not be
        // met until the relocation learned the four fields ticket 16 taught it.
        villagerRemembering()

        val report = audit()

        assertEquals(CHUNKS_RELOCATED, report.chunksAudited)
        assertEquals(COORDINATES_IN_THE_FIXTURE, report.coordinatesChecked)
    }

    @Test
    fun `a velocity and a uuid are not mistaken for the places they would sit inside`() {
        // The cow's velocity is (0.1, 0.0, -0.2) and its uuid is (1, 2, 3, 4);
        // read as coordinates both fall inside Secondary's old footprint, so an
        // audit that took every triple for a position would refuse this merge
        // every time. That it completes at all is most of the claim.
        villagerRemembering()

        merge()

        val cow = entityIn(relocated("entities", "entities"), "minecraft:cow")
        // A velocity comes through untouched: nothing moves it, and it is the one
        // list of three doubles in a chunk that is not a place.
        assertEquals(listOf(0.1, 0.0, -0.2), cow.getListOrEmpty("Motion").doubles())
        // The uuid is still four ints and not a place — but it is *not* still
        // (1, 2, 3, 4). MCA Selector re-rolls every relocated entity's uuid on
        // purpose, so that a chunk imported into a world that already holds the
        // same entity cannot collide with it. It had not been doing so here: the
        // null static field ticket 16 fixed made it abandon each entity before it
        // got that far, which is the same reason villager memories never moved.
        val uuid = cow.getIntArray("UUID").orElseThrow().toList()
        assertEquals(4, uuid.size)
        assertNotEquals(merged(Block(1, 2, 3)).toList(), uuid.take(3))
    }

    // ---- the cosmetic tier: repaired -----------------------------------------

    @Test
    fun `a lodestone compass is retargeted however deeply it is buried`() {
        villagerRemembering()

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
        villagerRemembering()

        merge()

        val target = blockEntityIn(relocated("region", "chunk"), "minecraft:chest")
            .getListOrEmpty("Items")
            .getCompoundOrEmpty(0)
            .let(::trackerIn)
        assertEquals("minecraft:overworld", target.getStringOr("dimension", ""))
    }

    @Test
    fun `a compass pointing into Secondary's End is named rather than guessed at`() {
        villagerRemembering()
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
        villagerRemembering()

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
        // An end gateway's exit portal. The relocation tool does not move it — it
        // looks for `ExitPortal` and 26.2 writes `exit_portal` — so this arrives in
        // Primary still naming a block in Secondary, exactly as a bee's hive did
        // before ticket 17 taught the tool about it.
        //
        // What makes it the right thing to leave here is that ChunkCompletion
        // *deliberately* does not finish it, and would finish a bee's hive: an exit
        // portal names a place in the End, and Secondary's End is discarded rather
        // than relocated, so there is no destination to point it at and the
        // overworld's offset would invent one. So this is a coordinate that is
        // genuinely left standing after every phase that could have moved it has
        // run, which is what makes the refusal below real rather than arranged.
        CoordinateBearingChunks.addEndGateway(
            save.levelDir,
            save.secondaryDimension(overworld),
            exitPortal = CoordinateBearingChunks.END_GATEWAY_EXIT,
        )
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
                "overworld: in chunk 517, -253: minecraft:end_gateway.exit_portal still names 88, 70, 56",
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

    // ---- the four fields ticket 16 taught the relocation ---------------------

    @Test
    fun `26_2's inline block positions arrive relocated, alongside the old spellings`() {
        // 1.21.5's InlineBlockPosFormatFix renamed `Leash` and the tile positions to
        // `leash` and `block_pos`. The stock 2.8 relocated only the old spellings, so
        // each of these arrived naming a block in Secondary — a leash tied to
        // nothing, a frame and a painting hung on air. The fixture carries *both*
        // spellings on the same entities at once, which is the claim that matters:
        // Secondary's chunks are a mixture of DataVersions, because vanilla rewrites
        // a chunk only when it loads one, so a fix that moved the new spelling
        // instead of the old would strand every chunk nobody has visited.
        villagerRemembering()
        CoordinateBearingChunks.addTheInlineBlockPositions(save.levelDir, save.secondaryDimension(overworld))

        merge()

        val entities = relocated("entities", "entities")
        val cow = entityIn(entities, "minecraft:cow")
        val frame = entityIn(entities, "minecraft:item_frame")
        val painting = entityIn(entities, "minecraft:painting")

        assertEquals(merged(CoordinateBearingChunks.LEASH_KNOT).toList(), intArrayIn(cow, "leash"))
        assertEquals(merged(CoordinateBearingChunks.ITEM_FRAME).toList(), intArrayIn(frame, "block_pos"))
        assertEquals(merged(CoordinateBearingChunks.PAINTING).toList(), intArrayIn(painting, "block_pos"))

        // And the spellings they replaced, on the same three entities, moved to
        // exactly the same places.
        assertEquals(merged(CoordinateBearingChunks.LEASH_KNOT).toList(), leashCompoundIn(cow))
        assertEquals(merged(CoordinateBearingChunks.ITEM_FRAME).toList(), tileIn(frame))
        assertEquals(merged(CoordinateBearingChunks.PAINTING).toList(), tileIn(painting))
    }

    @Test
    fun `a villager's memories arrive relocated, wrapped in value as 26_2 writes them`() {
        // A memory is written through `ExpirableValue`, which wraps it in `value`.
        // The stock 2.8 read `pos` off the memory itself, one level too shallow —
        // and never got that far anyway, because a static field it dereferences for
        // every entity was null, so each one was abandoned partway through. So a
        // villager arrived still remembering Secondary, and its trading hall was
        // dead. Both are fixed in the patched build (ticket 16).
        villagerRemembering(
            home = CoordinateBearingChunks.BED,
            jobSite = CoordinateBearingChunks.WORKSTATION,
            meetingPoint = CoordinateBearingChunks.MEETING_POINT,
            dimension = CoordinateBearingChunks.SECONDARY_OVERWORLD,
        )

        merge()

        val villager = entityIn(relocated("entities", "entities"), "minecraft:villager")
        val memories = villager.getCompoundOrEmpty("Brain").getCompoundOrEmpty("memories")
        for ((memory, was) in listOf(
            "minecraft:home" to CoordinateBearingChunks.BED,
            "minecraft:job_site" to CoordinateBearingChunks.WORKSTATION,
            "minecraft:meeting_point" to CoordinateBearingChunks.MEETING_POINT,
        )) {
            assertEquals(
                merged(was).toList(),
                intArrayIn(memories.getCompoundOrEmpty(memory).getCompoundOrEmpty("value"), "pos"),
                memory,
            )
        }
        // The merge completing is the other half: each of those memories has to
        // find the point-of-interest record that claims it, and those travelled in
        // a different file.
        assertTrue(Files.exists(save.mergeStamp))
    }

    @Test
    fun `a memory written before the value wrapper existed still relocates`() {
        // The same villager, with its memories written flat the way a save old
        // enough predates `ExpirableValue`'s wrapper. Nothing upgrades a chunk that
        // nothing loads, so this shape is still on disk in Secondary and the fix for
        // the wrapped one had to be additive rather than a replacement.
        CoordinateBearingChunks.addVillagerRememberingFlatly(
            save.levelDir,
            save.secondaryDimension(overworld),
            home = CoordinateBearingChunks.BED,
        )

        merge()

        val villager = entityIn(relocated("entities", "entities"), "minecraft:villager")
        assertEquals(
            merged(CoordinateBearingChunks.BED).toList(),
            intArrayIn(villager.getCompoundOrEmpty("Brain").getCompoundOrEmpty("memories")
                .getCompoundOrEmpty("minecraft:home"), "pos"),
        )
    }

    // ---- the cross-check across two files -----------------------------------

    @Test
    fun `a villager remembering a place no point of interest claims fails the merge`() {
        // Everything about this villager moved exactly as it should have, and the
        // trading hall is dead anyway: nothing claims the workstation it walks to.
        // That is the invariant no single chunk can establish, which is why it is
        // asked once the whole dimension has been read. The job site is given in
        // Secondary's coordinates like everything else, and lands at 9000, 64,
        // -4000 — inside no chunk that travelled, so no record claims it.
        villagerRemembering(
            home = CoordinateBearingChunks.BED,
            jobSite = Block(808, 64, 96),
            meetingPoint = CoordinateBearingChunks.MEETING_POINT,
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

    // ---- what the tool is still behind on, and what finishes it --------------

    @Test
    fun `a bee's hive and flower arrive relocated`() {
        // The fifth defect ticket 16 found and deliberately left. The tool moved an
        // entity's positions from a switch over entity ids with no case for a bee,
        // so a bee arrived in Primary still remembering a hive in Secondary — and
        // since bee nests generate in flower forests, birch forests and meadows,
        // that refused a real merge on ordinary terrain.
        CoordinateBearingChunks.addBee(
            save.levelDir,
            save.secondaryDimension(overworld),
            hive = CoordinateBearingChunks.LEASH_KNOT,
            flower = CoordinateBearingChunks.BEE_NEST_FLOWER,
        )

        merge()

        val bee = entityIn(relocated("entities", "entities"), "minecraft:bee")
        assertEquals(merged(CoordinateBearingChunks.LEASH_KNOT).toList(), intArrayIn(bee, "hive_pos"))
        assertEquals(merged(CoordinateBearingChunks.BEE_NEST_FLOWER).toList(), intArrayIn(bee, "flower_pos"))
    }

    @Test
    fun `a coordinate the tool does not know about is completed rather than refused over`() {
        // A bee nest, whose block entity the tool relocates from a *second*
        // hand-written switch that has not followed the renames at all: it looks for
        // `FlowerPos` and `Bees`/`EntityData` where 26.2 writes `flower_pos`, `bees`
        // and `entity_data`. Before ticket 17 this refused the merge. Now the
        // completion pass finishes it and the audit — unchanged, and running
        // afterwards — finds nothing left.
        beeNest()

        val report = merge()

        val nest = blockEntityIn(relocated("region", "chunk"), "minecraft:bee_nest")
        assertEquals(
            merged(CoordinateBearingChunks.BEE_NEST_FLOWER).toList(),
            intArrayIn(nest, CoordinateBearingChunks.FLOWER_POS),
        )
        assertEquals(merged(CoordinateBearingChunks.LEASH_KNOT).toList(), intArrayIn(storedBeeIn(nest), "hive_pos"))
        // And the audit still ran and still had nothing to refuse over.
        assertEquals(CHUNKS_RELOCATED, report.audit.chunksAudited)
    }

    @Test
    fun `a bee put away in a hive is not standing anywhere, and is not refused over`() {
        // The 221 coordinates that refused the live merge, all of one kind. A placed
        // hive's stored bee carries the `Pos` it had when it went in, nothing ever
        // reads it back — vanilla positions the bee at the hive on release — and the
        // relocation tool leaves it alone. Refusing over it is refusing over a
        // fossil, and it cost two full runs of an hour and three quarters each
        // before the refusal said how many kinds there were rather than showing
        // eight examples of one.
        beeNest()

        val report = merge()

        // The fossil stayed a fossil: unmoved, unrefused-over.
        val nest = blockEntityIn(relocated("region", "chunk"), "minecraft:bee_nest")
        val bee = storedBeeIn(nest)
        val pos = bee.getListOrEmpty(CoordinateBearingChunks.POS)
        assertEquals(CoordinateBearingChunks.BEE_NEST_FLOWER.x + 0.5, pos.getDoubleOr(0, 0.0))
        assertEquals(CoordinateBearingChunks.BEE_NEST_FLOWER.z + 0.5, pos.getDoubleOr(2, 0.0))
        // And the places it *remembers* are still places, so they still moved.
        assertEquals(merged(CoordinateBearingChunks.LEASH_KNOT).toList(), intArrayIn(bee, "hive_pos"))
        assertEquals(CHUNKS_RELOCATED, report.audit.chunksAudited)
    }

    @Test
    fun `the report names every coordinate the completion pass had to finish`() {
        // Ticket 03 refused to let the audit repair structural leftovers, because an
        // audit that patches over the relocation's gaps stops being able to tell
        // anyone the gaps are there. This is the answer to that: the merge finishes
        // and says exactly what it had to finish, so the operator reads "the tool has
        // fallen behind again" rather than nothing at all.
        beeNest()

        val report = merge().completion

        assertEquals(2, report.completed)
        assertEquals(1, report.chunksCompleted)
        assertEquals(
            setOf("minecraft:bee_nest.flower_pos — 1 coordinate", "minecraft:bee.hive_pos — 1 coordinate"),
            report.kinds.map { it.describe() }.toSet(),
        )
        assertTrue(
            report.lines().any {
                it.startsWith("coordinates completed") && it.contains("2 in 1 chunk")
            },
            "${report.lines()}",
        )
        assertTrue(
            report.lines().any { it.contains("MCA Selector has fallen behind what Minecraft writes") },
            "${report.lines()}",
        )
    }

    @Test
    fun `a merge with nothing to complete says the tool moved everything`() {
        // The ordinary case, and the one an operator should see: a silent zero would
        // read the same as a section that never ran.
        villagerRemembering()

        val report = merge().completion

        assertEquals(0, report.completed)
        assertEquals(
            listOf("coordinates completed    : none — the relocation tool moved everything it should have"),
            report.lines(),
        )
    }

    // ---- the report ----------------------------------------------------------

    @Test
    fun `the report separates what the merge repaired from what needs an operator`() {
        villagerRemembering()

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
        villagerRemembering()

        val report = merge()

        assertEquals("offset                   : x +8192, z -4096  (nether x +1024, z -512)", report.lines()[0])
        assertTrue(report.lines().contains("chunks audited           : $CHUNKS_RELOCATED"), "${report.lines()}")
    }

    // ---- reading the merged save back ---------------------------------------

    private fun merged(at: Block): Block = at.merged(OFFSET, overworld)

    /**
     * A villager remembering places in **Secondary's** own coordinates, which the
     * relocation is what moves.
     *
     * These used to be written already offset, because the relocation did not move
     * a memory at all and a test that wanted to say something about the audit had
     * to put the villager where a working relocation would have left it. The
     * patched tool moves them (ticket 16), so the fixture is now the honest one:
     * everything is written where Secondary has it, and every merged coordinate a
     * test asserts is one the tool produced.
     */
    private fun villagerRemembering(
        home: Block = CoordinateBearingChunks.BED,
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

    /** A bee nest holding one bee, in Secondary's own coordinates. */
    private fun beeNest() = CoordinateBearingChunks.addBeeNest(
        save.levelDir,
        save.secondaryDimension(overworld),
        flower = CoordinateBearingChunks.BEE_NEST_FLOWER,
        hive = CoordinateBearingChunks.LEASH_KNOT,
    )

    /** The one bee stored inside a nest, out of the occupant's own entity data. */
    private fun storedBeeIn(nest: CompoundTag): CompoundTag =
        nest.getListOrEmpty(CoordinateBearingChunks.BEES)
            .getCompoundOrEmpty(0)
            .getCompoundOrEmpty(CoordinateBearingChunks.ENTITY_DATA)

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

    /** An inline block position — an int array of three — by key. */
    private fun intArrayIn(tag: CompoundTag, key: String): List<Int> =
        tag.getIntArray(key).orElseThrow { AssertionError("no $key in $tag") }.toList()

    /** A leash in the spelling that predates the inline one: a compound of X/Y/Z. */
    private fun leashCompoundIn(entity: CompoundTag): List<Int> =
        entity.getCompoundOrEmpty("Leash").let { listOf(it.getIntOr("X", 0), it.getIntOr("Y", 0), it.getIntOr("Z", 0)) }

    /** A hung entity's tile in the spelling that predates the inline one. */
    private fun tileIn(entity: CompoundTag): List<Int> =
        listOf(entity.getIntOr("TileX", 0), entity.getIntOr("TileY", 0), entity.getIntOr("TileZ", 0))

    private fun ListTag.doubles(): List<Double> = (0 until size).map { getDoubleOr(it, Double.NaN) }
}
