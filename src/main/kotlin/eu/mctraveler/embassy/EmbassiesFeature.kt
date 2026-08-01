package eu.mctraveler.embassy

import eu.mctraveler.MCTraveler
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
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

    /** The bottom of the dimension: under here a player is falling through the void. */
    private const val VOID_Y = -64.0

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

    /**
     * Puts everyone still inside the dimension back where they came from — the
     * server is going down (spec story 6). Called before the stopping server
     * saves its players, so what is written is the place they really were.
     */
    fun returnEveryoneInside(server: MinecraftServer) {
        for (player in server.playerList.players.toList()) {
            if (isEmbassies(player.level())) EmbassyOrigins.sendHome(player)
        }
    }

    fun register() {
        RegionsFeature.addLookupGuard { world, _, _, _, found ->
            if (world == RegionWorlds.EMBASSIES) found ?: worldRegion else found
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            EmbassyCommands.register(dispatcher)
        }
        EmbassyAnchors.register()
        // Nothing hurts a player here, of any kind (spec story 2). Nucleus
        // cancelled every damage event in the world; deviation 1 makes this the
        // whole of the ~40 per-world damage gamerules Bukkit let it set.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            entity !is ServerPlayer || !isEmbassies(entity.level())
        }
        // Falling off a plot (spec story 5). Read from every player's live
        // position once a tick, as the region tracker's sweep is: however a
        // player got under the world, they are on their way home.
        ServerTickEvents.END_SERVER_TICK.register { server ->
            for (player in server.playerList.players) {
                if (isEmbassies(player.level()) && player.y < VOID_Y) EmbassyOrigins.sendHome(player)
            }
        }
        // Logging out inside (spec story 6). LEAVE is the head of the player
        // list's removal, which is one statement ahead of the save — so the
        // origin is where the save finds them, and where they log back in.
        ServerPlayerEvents.LEAVE.register { player ->
            if (isEmbassies(player.level())) EmbassyOrigins.sendHome(player)
            EmbassyOrigins.forget(player.uuid)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register(::returnEveryoneInside)
    }

    private fun id(path: String): Identifier =
        Identifier.fromNamespaceAndPath(MCTraveler.MOD_ID, path)
}
