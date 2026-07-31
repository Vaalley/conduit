package eu.mctraveler.embassy

import eu.mctraveler.MCTraveler
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome

/**
 * The Embassies dimension (CONTEXT.md; ADR 0003): the plot museum players are
 * only ever teleported into, and always returned out of.
 *
 * The place itself is datapack JSON in the mod jar — a flat void of [BIOME]
 * under a dimension type that is the overworld's with the day taken out of it.
 * What JSON cannot say is here: the whole dimension is one region nobody is a
 * member of, and nothing that happens in it may hurt a player.
 */
object EmbassiesFeature {

    /** The dimension. Outside every World's trio, so `Worlds.worldOf` is null for it. */
    val DIMENSION: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, id("embassies"))

    /** The dimension's only biome: plains, with nothing spawning and no weather. */
    val BIOME: ResourceKey<Biome> = ResourceKey.create(Registries.BIOME, id("embassies_plains"))

    /**
     * The whole dimension as a region, for every position no real embassy
     * covers: protection turns everyone away from the void (nobody is a
     * member), and `NO_SCOREBOARD` keeps the sidebar off the screen between
     * plots. In memory only — it is never in the region tree, so no save can
     * write it to `regions.json`.
     *
     * Its corners are Nucleus's: 0/0, never read, because the guard hands this
     * region back by position rather than by containment.
     */
    val worldRegion: Region = Region(
        title = "Embassies World",
        world = RegionWorlds.EMBASSIES,
        startX = 0,
        startZ = 0,
        endX = 0,
        endZ = 0,
    ).also { it.flags.add("NO_SCOREBOARD") }

    /** Whether [level] is the embassies dimension. */
    fun isEmbassies(level: Level): Boolean = level.dimension() == DIMENSION

    fun register() {
        RegionsFeature.addLookupGuard { world, _, _, _, found ->
            if (world == RegionWorlds.EMBASSIES) found ?: worldRegion else found
        }
        // Nothing hurts a player here, of any kind (spec story 2). Nucleus
        // cancelled every damage event in the world; deviation 1 makes this the
        // whole of the ~40 per-world damage gamerules Bukkit let it set.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            entity !is ServerPlayer || !isEmbassies(entity.level())
        }
    }

    private fun id(path: String): Identifier =
        Identifier.fromNamespaceAndPath(MCTraveler.MOD_ID, path)
}
