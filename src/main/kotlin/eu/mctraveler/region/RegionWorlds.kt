package eu.mctraveler.region

import eu.mctraveler.MCTraveler
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
 *
 * The out-of-trio embassies dimension (ADR 0003) is here for the same reason:
 * Nucleus stored its embassy regions under its Bukkit world name, `embassies`,
 * so keeping that string is what lets the twenty imported embassies be found
 * where they stand.
 */
object RegionWorlds {
    private fun modDimension(path: String): ResourceKey<Level> =
        ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(MCTraveler.MOD_ID, path))

    /** The legacy world string of the embassies dimension — Nucleus's world name. */
    const val EMBASSIES = "embassies"

    private val legacyNames: Map<ResourceKey<Level>, String> = mapOf(
        Level.OVERWORLD to "world",
        Level.NETHER to "world_nether",
        Level.END to "world_the_end",
        modDimension("secondary") to "last",
        modDimension("secondary_nether") to "last_nether",
        modDimension("secondary_end") to "last_the_end",
        modDimension("embassies") to EMBASSIES,
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
     * with the Portal's exact mapping. The embassies dimension is in no World
     * and has no trio to name a role in, so it is simply itself.
     */
    fun locateInfo(world: String): String {
        if (world == EMBASSIES) return EMBASSIES
        val server = if (isSecondaryWorld(world)) "secondary" else "primary"
        val dimension = when {
            world.contains("nether") -> "nether"
            world.contains("end") -> "end"
            else -> "overworld"
        }
        return "$server/$dimension"
    }
}
