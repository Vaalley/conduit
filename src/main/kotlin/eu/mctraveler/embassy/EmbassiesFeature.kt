package eu.mctraveler.embassy

import eu.mctraveler.MCTraveler
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome

/**
 * The Embassies dimension (CONTEXT.md; ADR 0003): the plot museum players are
 * only ever teleported into and always returned out of.
 *
 * The place itself is datapack JSON in the mod jar — a flat void of
 * [BIOME], under a dimension type that is the overworld's with the sun nailed
 * to noon. What cannot be said in JSON is here.
 */
object EmbassiesFeature {

    /** The dimension. Outside every World's trio, so `Worlds.worldOf` is null for it. */
    val DIMENSION: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, id("embassies"))

    /** The dimension's only biome: plains, with nothing spawning and no weather. */
    val BIOME: ResourceKey<Biome> = ResourceKey.create(Registries.BIOME, id("embassies_plains"))

    private fun id(path: String): Identifier =
        Identifier.fromNamespaceAndPath(MCTraveler.MOD_ID, path)
}
