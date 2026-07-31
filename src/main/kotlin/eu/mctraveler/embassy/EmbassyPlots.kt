package eu.mctraveler.embassy

import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.properties.SlabType

/**
 * Where an embassy goes, and what one is made of — Nucleus's
 * `getNextAvailablePlotCoords` and `populateChunk`.
 *
 * One embassy is one chunk. Plots are handed out along an outward spiral from
 * chunk (0, 0), and a chunk is free while the region lookup at its centre still
 * answers with the synthetic world region — which is to say, while no embassy
 * region has been laid over it. That test is the whole allocator: the ground
 * itself is never consulted, so a plot whose blocks were cleared by
 * `/embassy delete` is offered again on the next `/embassy create`.
 */
object EmbassyPlots {

    /** The deck the plot is built on, and the level a player stands at. */
    const val FLOOR_Y = 0

    /** The plot's floor slab: the bottom of the dimension. */
    private const val BEDROCK_Y = -64

    /** Local x/z of the grass square's low corner, and of its high corner. */
    const val GRASS_MIN = 3
    const val GRASS_MAX = 13

    /** Local x/z of the stair frame around the grass. */
    private const val FRAME_MIN = 2
    private const val FRAME_MAX = 14

    /** Local x/z of the respawn anchor, in the middle of the chunk. */
    const val ANCHOR_LOCAL = 8

    /** The anchor is built full, as Nucleus built it. */
    const val ANCHOR_CHARGES = 4

    /**
     * Bulk fill: the client is told, but no neighbour is. Every block placed
     * this way is an inert cube going into empty void, so there is nothing for
     * an update to tell — and a plot is a hundred thousand of them.
     */
    private const val FILL = Block.UPDATE_CLIENTS

    /**
     * Every plot chunk, in the order Nucleus offered them: out from (0, 0),
     * turning left each time a leg runs out and lengthening the leg every
     * second turn. Infinite — the caller stops at the first free one.
     */
    fun spiral(): Sequence<ChunkPos> = sequence {
        var x = 0
        var z = 0
        var direction = 0
        var currentStep = 0
        var legLength = 1
        var legCounter = 0
        while (true) {
            yield(ChunkPos(x, z))
            when (direction) {
                0 -> x += 1
                1 -> z += 1
                2 -> x -= 1
                else -> z -= 1
            }
            currentStep += 1
            if (currentStep == legLength) {
                currentStep = 0
                direction = (direction + 1) % 4
                legCounter += 1
                if (legCounter % 2 == 0) legLength += 1
            }
        }
    }

    /**
     * Whether [plot] is still unclaimed: the lookup at its centre gives back
     * the very instance the embassies void answers with, rather than a region
     * somebody owns. Identity, not equality — the synthetic region is a single
     * shared object handed out by position (see [EmbassiesFeature.worldRegion]).
     */
    fun isFree(plot: ChunkPos): Boolean =
        RegionsFeature.regionAt(
            RegionWorlds.EMBASSIES,
            plot.x * 16 + ANCHOR_LOCAL,
            FLOOR_Y,
            plot.z * 16 + ANCHOR_LOCAL,
        ) === EmbassiesFeature.worldRegion

    /** The next plot to build on. */
    fun nextFreePlot(): ChunkPos = spiral().first(::isFree)

    /**
     * Builds the plot: bedrock floor, dirt fill, a smooth quartz deck, an 11×11
     * lawn, a blackstone stair frame around it and a full respawn anchor in the
     * middle — Nucleus's palette, block for block (spec story 10).
     *
     * The frame's facings are what Nucleus's rotations produced from a stair
     * that starts facing north: 180° for the north edge, 90° clockwise for the
     * west, none for the south, 90° counter-clockwise for the east. The stairs
     * are the one thing placed with neighbour updates, so vanilla mitres the
     * four corners exactly as it did under Bukkit.
     */
    fun populate(level: ServerLevel, plot: ChunkPos) {
        val originX = plot.x * 16
        val originZ = plot.z * 16
        val pos = BlockPos.MutableBlockPos()
        // Read here rather than held on the object: the spiral above is pure
        // arithmetic, and touching the block registry to initialise it would
        // drag the whole game bootstrap into its unit tests.
        val dirt = Blocks.DIRT.defaultBlockState()
        val bedrock = Blocks.BEDROCK.defaultBlockState()
        val grass = Blocks.GRASS_BLOCK.defaultBlockState()
        val slab = Blocks.SMOOTH_QUARTZ_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
        val anchor = Blocks.RESPAWN_ANCHOR.defaultBlockState()
            .setValue(RespawnAnchorBlock.CHARGE, ANCHOR_CHARGES)

        for (x in 0..15) {
            for (z in 0..15) {
                for (y in (BEDROCK_Y + 1)..-1) {
                    level.setBlock(pos.set(originX + x, y, originZ + z), dirt, FILL)
                }
                level.setBlock(pos.set(originX + x, BEDROCK_Y, originZ + z), bedrock, FILL)
                level.setBlock(pos.set(originX + x, FLOOR_Y, originZ + z), slab, FILL)
            }
        }
        for (x in GRASS_MIN..GRASS_MAX) {
            for (z in GRASS_MIN..GRASS_MAX) {
                level.setBlock(pos.set(originX + x, FLOOR_Y, originZ + z), grass, FILL)
            }
        }

        for (x in FRAME_MIN..FRAME_MAX) {
            stair(level, originX + x, originZ + FRAME_MIN, Direction.SOUTH)
            stair(level, originX + x, originZ + FRAME_MAX, Direction.NORTH)
        }
        for (z in GRASS_MIN..GRASS_MAX) {
            stair(level, originX + FRAME_MIN, originZ + z, Direction.EAST)
            stair(level, originX + FRAME_MAX, originZ + z, Direction.WEST)
        }

        level.setBlockAndUpdate(BlockPos(originX + ANCHOR_LOCAL, FLOOR_Y, originZ + ANCHOR_LOCAL), anchor)
    }

    /**
     * Takes the plot back down to nothing, over the dimension's whole build
     * height — what `/embassy delete` leaves behind (spec story 18). Bulk-flagged
     * like the build: there is nothing left standing for an update to reach.
     */
    fun clear(level: ServerLevel, plot: ChunkPos) {
        val originX = plot.x * 16
        val originZ = plot.z * 16
        val air = Blocks.AIR.defaultBlockState()
        val pos = BlockPos.MutableBlockPos()
        for (x in 0..15) {
            for (z in 0..15) {
                for (y in level.minY..level.maxY) {
                    level.setBlock(pos.set(originX + x, y, originZ + z), air, FILL)
                }
            }
        }
    }

    private fun stair(level: ServerLevel, x: Int, z: Int, facing: Direction) {
        level.setBlockAndUpdate(
            BlockPos(x, FLOOR_Y, z),
            Blocks.BLACKSTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing),
        )
    }
}
