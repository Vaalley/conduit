package eu.mctraveler.region

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * The one place dimensions and the Portal's legacy world strings meet.
 *
 * Regions are stored under the Portal's strings — `world`, `world_nether`,
 * `world_the_end` for the Primary trio and `last`, `last_nether`,
 * `last_the_end` for Secondary — so migrated `regions.json` data reads
 * unchanged. Primary is the vanilla trio; the Secondary dimension ids below
 * anticipate ticket 04's datapack trio (adjust here, and only here, if that
 * ticket lands on different ids).
 */
object RegionWorlds {
    private fun secondary(path: String): ResourceKey<Level> =
        ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("mctraveler", path))

    private val legacyNames: Map<ResourceKey<Level>, String> = mapOf(
        Level.OVERWORLD to "world",
        Level.NETHER to "world_nether",
        Level.END to "world_the_end",
        secondary("secondary") to "last",
        secondary("secondary_nether") to "last_nether",
        secondary("secondary_end") to "last_the_end",
    )

    /**
     * The legacy world string regions in [dimension] live under. A dimension
     * outside both trios keeps its own id string — distinct from every legacy
     * name, so its regions can never bleed into a real World.
     */
    fun legacyName(dimension: ResourceKey<Level>): String =
        legacyNames[dimension] ?: dimension.identifier().toString()

    /** Whether the legacy world string belongs to the Secondary World. */
    fun isSecondaryWorld(world: String): Boolean = world.startsWith("last")

    /**
     * `/rg locate`'s `server/dimension` rendering of a legacy world string,
     * with the Portal's exact mapping.
     */
    fun locateInfo(world: String): String {
        val server = if (isSecondaryWorld(world)) "secondary" else "primary"
        val dimension = when {
            world.contains("nether") -> "nether"
            world.contains("end") -> "end"
            else -> "overworld"
        }
        return "$server/$dimension"
    }
}
