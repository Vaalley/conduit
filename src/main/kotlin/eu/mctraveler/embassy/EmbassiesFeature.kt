package eu.mctraveler.embassy

import eu.mctraveler.MCTraveler
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.monster.Enemy
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

    /** The dimension. Outside the vanilla trio, and not somewhere players live (ADR 0003). */
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
     * Whether a spawn is the world populating itself, rather than somebody
     * putting something somewhere. The museum does no populating of its own
     * (deviation 1), whatever the chunk it happens over thinks it is.
     */
    private fun isTheWorldsOwnDoing(reason: EntitySpawnReason?): Boolean = when (reason) {
        EntitySpawnReason.NATURAL,
        EntitySpawnReason.CHUNK_GENERATION,
        EntitySpawnReason.SPAWNER,
        EntitySpawnReason.TRIAL_SPAWNER,
        EntitySpawnReason.PATROL -> true
        else -> false
    }

    /**
     * Whether the embassies dimension will hold [entity], arriving for [reason].
     *
     * Deviation 1 said the empty biome spawners were the whole of "no natural
     * mobs", and for a dimension the server generates itself they would be. The
     * plots are not that: they are Nucleus's chunks, copied in whole by
     * [eu.mctraveler.importer.EmbassyImport], and a generated chunk carries its
     * own biomes in its sections. Every imported plot therefore still says
     * `minecraft:plains` — spawn list and all — and only the empty air the
     * server generated around them was ever [BIOME]. So the rule cannot live in
     * the biome; it lives here, where the dimension is the whole of the test.
     *
     * Two things are turned away. Anything the world spawns by itself, which is
     * the museum standing still. And anything hostile whatever brought it —
     * including [EntitySpawnReason.LOAD], the save itself, which is how the
     * creepers and slimes the plots were imported with are shown the door the
     * first time their chunk loads and are gone the next time it is written.
     *
     * Everything a person put there stays: item frames, paintings, armour
     * stands, and the animals somebody penned on a plot as part of the exhibit.
     *
     * A null [reason] is an arrival that named none — nothing the world can be
     * held to have done, so only the hostile half of the rule can judge it.
     */
    fun accepts(entity: Entity, reason: EntitySpawnReason?): Boolean =
        entity !is Enemy && !isTheWorldsOwnDoing(reason)

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
        // Nothing spawns here and nothing hostile stays (spec story 2). The
        // biome cannot say so for the imported plots, so [accepts] does.
        ServerEntityEvents.ALLOW_LOAD.register { entity, level, reason, _ ->
            !isEmbassies(level) || accepts(entity, reason)
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
