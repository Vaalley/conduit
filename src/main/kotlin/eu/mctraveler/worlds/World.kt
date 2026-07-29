package eu.mctraveler.worlds

import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * The place in a World's trio a dimension plays: every World is a complete
 * overworld/nether/end set, and player state that is per-World (the Per-World
 * Bucket) records dimensions by role so it is meaningful in any World.
 *
 * [id] is the persisted form (the bucket's `dimension` field). [vanilla] is the
 * dimension Minecraft itself gives the role — Primary's trio, and the key
 * vanilla's own hardcoded portal comparisons are written against.
 */
enum class DimensionRole(val id: String, val vanilla: ResourceKey<Level>) {
    OVERWORLD("overworld", Level.OVERWORLD),
    NETHER("nether", Level.NETHER),
    END("end", Level.END);

    companion object {
        fun fromId(id: String): DimensionRole? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One of the Worlds players inhabit and Travel between — a trio of dimensions
 * on the single server. [id] is the Portal's persisted world id (`"primary"`/
 * `"secondary"`, the `lastServer` values); [displayName] is the player-facing
 * name (`"Primary"`/`"Secondary"`).
 */
class World internal constructor(
    val id: String,
    val displayName: String,
    private val trio: Map<DimensionRole, ResourceKey<Level>>,
) {
    fun dimension(role: DimensionRole): ResourceKey<Level> = trio.getValue(role)

    /** The role [dimension] plays in this World, or null if it is not part of this World. */
    fun roleOf(dimension: ResourceKey<Level>): DimensionRole? =
        trio.entries.firstOrNull { it.value == dimension }?.key

    override fun toString(): String = "World($id)"
}
