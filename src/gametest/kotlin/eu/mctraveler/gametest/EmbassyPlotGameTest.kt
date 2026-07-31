package eu.mctraveler.gametest

import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.embassy.EmbassyPlots
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.properties.SlabType

/**
 * The plot itself: the blocks Nucleus's `populateChunk` laid down (spec story
 * 10), and the spiral allocator's one rule — a chunk is free until a region
 * covers it.
 *
 * Each test builds in its own far-out chunk lane, well past both the spiral's
 * opening plots and ticket 01's coordinate lanes, so a real `/embassy create`
 * running alongside cannot land on top of it.
 */
class EmbassyPlotGameTest {

    @GameTest
    fun aPlotIsBuiltFromNucleusOwnPalette(helper: GameTestHelper) {
        val level = embassies(helper)
        val plot = ChunkPos(500, 500)
        EmbassyPlots.populate(level, plot)

        // Bedrock floor across the whole chunk, dirt all the way up to the deck.
        for (corner in listOf(0 to 0, 15 to 0, 0 to 15, 15 to 15, 8 to 8)) {
            helper.assertValueEqual(
                blockAt(level, plot, corner.first, -64, corner.second),
                Blocks.BEDROCK,
                "the plot floor at local ${corner.first}/${corner.second}",
            )
        }
        for (y in listOf(-63, -32, -1)) {
            helper.assertValueEqual(blockAt(level, plot, 0, y, 0), Blocks.DIRT, "the plot fill at y=$y")
        }

        // A smooth quartz deck over the chunk, bottom slabs, except where the
        // lawn and its frame replace it.
        val deck = level.getBlockState(absolute(plot, 0, EmbassyPlots.FLOOR_Y, 0))
        helper.assertValueEqual(deck.block, Blocks.SMOOTH_QUARTZ_SLAB, "the plot deck")
        helper.assertValueEqual(deck.getValue(SlabBlock.TYPE), SlabType.BOTTOM, "the deck slab half")
        helper.assertValueEqual(
            blockAt(level, plot, 15, EmbassyPlots.FLOOR_Y, 15),
            Blocks.SMOOTH_QUARTZ_SLAB,
            "the plot deck at the far corner",
        )

        // The 11x11 lawn, local 3..13.
        for (spot in listOf(3 to 3, 13 to 13, 3 to 13, 13 to 3, 5 to 9)) {
            helper.assertValueEqual(
                blockAt(level, plot, spot.first, EmbassyPlots.FLOOR_Y, spot.second),
                Blocks.GRASS_BLOCK,
                "the lawn at local ${spot.first}/${spot.second}",
            )
        }
        // ...and nothing outside it.
        helper.assertValueEqual(
            blockAt(level, plot, 2, EmbassyPlots.FLOOR_Y, 3),
            Blocks.BLACKSTONE_STAIRS,
            "the block just outside the lawn's west edge",
        )

        // The frame, with the facings Nucleus's rotations produced.
        helper.assertValueEqual(stairFacing(level, plot, 8, 2), Direction.SOUTH, "the north edge's facing")
        helper.assertValueEqual(stairFacing(level, plot, 2, 8), Direction.EAST, "the west edge's facing")
        helper.assertValueEqual(stairFacing(level, plot, 8, 14), Direction.NORTH, "the south edge's facing")
        helper.assertValueEqual(stairFacing(level, plot, 14, 8), Direction.WEST, "the east edge's facing")
        // The frame runs the full 2..14 on the north and south edges, and the
        // corners belong to them rather than to the sides.
        helper.assertValueEqual(stairFacing(level, plot, 2, 2), Direction.SOUTH, "the north-west corner's facing")
        helper.assertValueEqual(stairFacing(level, plot, 14, 2), Direction.SOUTH, "the north-east corner's facing")
        helper.assertValueEqual(stairFacing(level, plot, 2, 14), Direction.NORTH, "the south-west corner's facing")
        helper.assertValueEqual(stairFacing(level, plot, 14, 14), Direction.NORTH, "the south-east corner's facing")

        // A full respawn anchor in the middle.
        val anchor = level.getBlockState(absolute(plot, 8, EmbassyPlots.FLOOR_Y, 8))
        helper.assertValueEqual(anchor.block, Blocks.RESPAWN_ANCHOR, "the block in the middle of the plot")
        helper.assertValueEqual(anchor.getValue(RespawnAnchorBlock.CHARGE), 4, "the anchor's charges")

        EmbassyPlots.clear(level, plot)
        helper.succeed()
    }

    @GameTest
    fun clearingAPlotTakesItDownOverTheWholeBuildHeight(helper: GameTestHelper) {
        val level = embassies(helper)
        val plot = ChunkPos(510, 510)
        EmbassyPlots.populate(level, plot)
        EmbassyPlots.clear(level, plot)

        for (y in listOf(level.minY, -63, 0, 100, level.maxY)) {
            helper.assertTrue(
                level.getBlockState(absolute(plot, 8, y, 8)).isAir,
                "a deleted embassy left something behind at y=$y",
            )
        }
        helper.assertTrue(
            level.getBlockState(absolute(plot, 0, -64, 0)).isAir,
            "a deleted embassy left its bedrock floor behind",
        )
        helper.succeed()
    }

    @GameTest
    fun aPlotIsFreeUntilARegionCoversIt(helper: GameTestHelper) {
        // Allocation reads the region tree, never the ground: the plot built
        // above is still on offer because nobody's region covers it.
        val plot = ChunkPos(520, 520)
        helper.assertTrue(EmbassyPlots.isFree(plot), "an unclaimed plot should be free")

        val service = RegionsFeature.requireService()
        val region = embassyRegionOver(plot)
        service.add(region, parent = null)
        try {
            helper.assertFalse(EmbassyPlots.isFree(plot), "a plot under a region should not be free")
        } finally {
            service.remove(region)
        }
        helper.assertTrue(EmbassyPlots.isFree(plot), "the plot should be on offer again once the region is gone")
        helper.succeed()
    }

    @GameTest
    fun allocationWalksTheSpiralPastTheClaimedPlots(helper: GameTestHelper) {
        val service = RegionsFeature.requireService()
        val first = EmbassyPlots.nextFreePlot()
        helper.assertValueEqual(
            first,
            EmbassyPlots.spiral().first { EmbassyPlots.isFree(it) },
            "the next free plot should be the first free one on the spiral",
        )

        val claim = embassyRegionOver(first)
        service.add(claim, parent = null)
        try {
            val second = EmbassyPlots.nextFreePlot()
            helper.assertFalse(second == first, "allocation offered a plot that had just been claimed")
            // The spiral is walked in order, so the new plot is strictly later.
            val order = EmbassyPlots.spiral().take(64).toList()
            helper.assertTrue(
                order.indexOf(second) > order.indexOf(first),
                "allocation went backwards along the spiral",
            )
        } finally {
            service.remove(claim)
        }
        helper.succeed()
    }

    // ---- helpers ----

    private fun embassies(helper: GameTestHelper): ServerLevel =
        checkNotNull(helper.level.server.getLevel(EmbassiesFeature.DIMENSION)) {
            "the ${EmbassiesFeature.DIMENSION.identifier()} dimension is not loaded on the server"
        }

    private fun absolute(plot: ChunkPos, x: Int, y: Int, z: Int): BlockPos =
        BlockPos(plot.x * 16 + x, y, plot.z * 16 + z)

    private fun blockAt(level: ServerLevel, plot: ChunkPos, x: Int, y: Int, z: Int) =
        level.getBlockState(absolute(plot, x, y, z)).block

    private fun stairFacing(level: ServerLevel, plot: ChunkPos, x: Int, z: Int): Direction =
        level.getBlockState(absolute(plot, x, EmbassyPlots.FLOOR_Y, z)).getValue(StairBlock.FACING)

    /** An embassy region over [plot], exactly as `/embassy create` shapes one. */
    private fun embassyRegionOver(plot: ChunkPos): Region =
        Region(
            title = "Test Claim ${plot.x}/${plot.z}",
            world = RegionWorlds.EMBASSIES,
            startX = plot.x * 16 + EmbassyPlots.GRASS_MIN,
            startZ = plot.z * 16 + EmbassyPlots.GRASS_MIN,
            endX = plot.x * 16 + EmbassyPlots.GRASS_MAX,
            endZ = plot.z * 16 + EmbassyPlots.GRASS_MAX,
        ).also { it.flags.add("EMBASSY") }
}
