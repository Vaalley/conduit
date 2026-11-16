package eu.mctraveler.rtp

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB

/** An axis-aligned block cuboid, both corners included: the footprint of a wilderness portal. */
data class PortalBox(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
) {
    val sizeX: Int get() = maxX - minX + 1
    val sizeY: Int get() = maxY - minY + 1
    val sizeZ: Int get() = maxZ - minZ + 1

    val volume: Long get() = sizeX.toLong() * sizeY * sizeZ

    fun contains(x: Int, y: Int, z: Int): Boolean =
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    fun contains(pos: BlockPos): Boolean = contains(pos.x, pos.y, pos.z)

    fun intersects(other: PortalBox): Boolean =
        minX <= other.maxX && maxX >= other.minX &&
            minY <= other.maxY && maxY >= other.minY &&
            minZ <= other.maxZ && maxZ >= other.minZ

    /** Squared distance from a point to the nearest point of the box; 0 inside it. */
    fun distanceSq(x: Double, y: Double, z: Double): Double {
        val dx = axis(x, minX.toDouble(), maxX + 1.0)
        val dy = axis(y, minY.toDouble(), maxY + 1.0)
        val dz = axis(z, minZ.toDouble(), maxZ + 1.0)
        return dx * dx + dy * dy + dz * dz
    }

    fun toAabb(): AABB = AABB(minX.toDouble(), minY.toDouble(), minZ.toDouble(), maxX + 1.0, maxY + 1.0, maxZ + 1.0)

    private fun axis(value: Double, low: Double, high: Double): Double = when {
        value < low -> low - value
        value > high -> value - high
        else -> 0.0
    }

    companion object {
        /** The box spanned by two opposite corners given in any order. */
        fun of(a: BlockPos, b: BlockPos) = PortalBox(
            minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z),
            maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z),
        )
    }
}
