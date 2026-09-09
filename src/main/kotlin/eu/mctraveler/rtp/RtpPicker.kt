package eu.mctraveler.rtp

import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.worlds.Landing
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Finds somewhere random to stand: a surface column within the configured ring
 * around spawn whose ground is solid and dry, with headroom, inside the world
 * border, and on nobody's land.
 *
 * Each try loads (and, most of the time out there, generates) one chunk on
 * the server thread, so tries are capped rather than retried until success.
 */
object RtpPicker {

    const val MAX_ATTEMPTS = 10
    const val END_MAX_ATTEMPTS = 25

    data class Ring(val minDistance: Int, val radius: Int)

    /** Blocks that are ground you can stand on but would rather not land on. */
    private val hostileGround = setOf(
        Blocks.MAGMA_BLOCK,
        Blocks.CACTUS,
        Blocks.FIRE,
        Blocks.SOUL_FIRE,
        Blocks.CAMPFIRE,
        Blocks.SWEET_BERRY_BUSH,
        Blocks.WITHER_ROSE,
        Blocks.POWDER_SNOW,
        Blocks.POINTED_DRIPSTONE,
        Blocks.LAVA,
    )

    /**
     * A landing for [player] somewhere new in [level], keeping their facing, or
     * null when [MAX_ATTEMPTS] columns were all unfit.
     */
    fun pick(
        player: ServerPlayer,
        level: ServerLevel,
        ring: Ring,
        attempts: Int,
        centerX: Int,
        centerZ: Int,
        random: Random = Random,
    ): Landing? {
        repeat(attempts) {
            val (x, z) = candidate(random, ring, centerX, centerZ)
            val y = surfaceAt(level, x, z) ?: return@repeat
            if (!isSafeColumn(level, x, y, z)) return@repeat
            if (isClaimed(level, x, y, z)) return@repeat
            return Landing(level, x + 0.5, y.toDouble(), z + 0.5, player.yRot, player.xRot)
        }
        return null
    }

    /**
     * A uniformly distributed point of the ring `minDistance..radius` around
     * ([centerX], [centerZ]) — uniform over area, so the outer, larger part of
     * the ring is not under-visited.
     */
    fun candidate(random: Random, ring: Ring, centerX: Int, centerZ: Int): Pair<Int, Int> {
        val min = ring.minDistance.toDouble()
        val max = ring.radius.toDouble()
        val distance = sqrt(random.nextDouble() * (max * max - min * min) + min * min)
        val angle = random.nextDouble() * 2 * Math.PI
        return (centerX + (cos(angle) * distance).toInt()) to (centerZ + (sin(angle) * distance).toInt())
    }

    /**
     * The y a player's feet would rest at in column ([x], [z]) — one above the
     * highest motion-blocking non-leaf block — or null when the column is
     * outside the border or bottoms out in the void.
     */
    private fun surfaceAt(level: ServerLevel, x: Int, z: Int): Int? {
        if (!level.worldBorder.isWithinBounds(x.toDouble(), z.toDouble())) return null
        val chunk = level.getChunk(x shr 4, z shr 4)
        val y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15) + 1
        return surfaceY(y, level.minY, level.maxY)
    }

    fun surfaceY(y: Int, minY: Int, maxY: Int): Int? =
        y.takeIf { it > minY + 1 && it < maxY - 1 }

    private fun isSafeColumn(level: ServerLevel, x: Int, y: Int, z: Int): Boolean =
        isSafeGround(
            below = level.getBlockState(BlockPos(x, y - 1, z)),
            feet = level.getBlockState(BlockPos(x, y, z)),
            head = level.getBlockState(BlockPos(x, y + 1, z)),
        )

    /** Solid, dry, harmless ground under two blocks of air. */
    fun isSafeGround(below: BlockState, feet: BlockState, head: BlockState): Boolean =
        below.isSolid &&
            !below.liquid() &&
            below.block !in hostileGround &&
            feet.isAir &&
            head.isAir

    private fun isClaimed(level: ServerLevel, x: Int, y: Int, z: Int): Boolean {
        val service = RegionsFeature.service ?: return false
        return service.regionAt(RegionWorlds.legacyName(level.dimension()), x, y, z) != null
    }
}
