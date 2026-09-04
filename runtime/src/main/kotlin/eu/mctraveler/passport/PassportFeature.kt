package eu.mctraveler.passport

import eu.mctraveler.Conduit
import eu.mctraveler.MCTraveler
import eu.mctraveler.reloadable
import eu.mctraveler.region.RegionTracker
import eu.mctraveler.region.RegionsFeature
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import java.util.UUID

object PassportFeature {
    private const val FLUSH_INTERVAL_TICKS = 1200L
    private const val SAMPLE_INTERVAL_TICKS = 20L
    private const val MAX_TICK_DISTANCE = 100.0

    private class State(player: ServerPlayer) {
        var lastPos: Vec3 = player.position()
        var lastDimension: String = dimensionOf(player)
    }

    private val states = HashMap<UUID, State>()

    fun register() {
        ServerPlayerEvents.JOIN.reloadable.register(::onJoin)
        ServerPlayConnectionEvents.DISCONNECT.reloadable.register { handler, _ ->
            states.remove(handler.player.uuid)
            MCTraveler.persistence?.passports?.flushDirty()
        }
        ServerLivingEntityEvents.AFTER_DEATH.reloadable.register { entity, _ ->
            val player = entity as? ServerPlayer ?: return@register
            val persistence = MCTraveler.persistence ?: return@register
            val passport = persistence.passports.getOrCreate(
                player.uuid,
                persistence.players.firstJoin(player.uuid) ?: System.currentTimeMillis(),
            )
            passport.deaths++
            persistence.passports.markDirty(player.uuid)
        }
        ServerTickEvents.END_SERVER_TICK.reloadable.register(::onEndServerTick)
        ServerLifecycleEvents.SERVER_STOPPING.reloadable.register {
            MCTraveler.persistence?.passports?.flushDirty()
        }
        ServerLifecycleEvents.SERVER_STOPPED.reloadable.register { states.clear() }
        PassportCommand.register()
        Conduit.onHotActivate { server ->
            server.playerList.players.forEach(::onJoin)
        }
    }

    private fun onJoin(player: ServerPlayer) {
        val persistence = MCTraveler.persistence ?: return
        persistence.passports.getOrCreate(
            player.uuid,
            persistence.players.firstJoin(player.uuid) ?: System.currentTimeMillis(),
        )
        states[player.uuid] = State(player)
    }

    private fun onEndServerTick(server: MinecraftServer) {
        val persistence = MCTraveler.persistence ?: return
        val tick = server.tickCount.toLong()
        for (player in server.playerList.players) {
            val state = states[player.uuid] ?: run {
                onJoin(player)
                states[player.uuid] ?: continue
            }
            val passport = persistence.passports.getOrCreate(
                player.uuid,
                persistence.players.firstJoin(player.uuid) ?: System.currentTimeMillis(),
            )
            if (player.gameMode.gameModeForPlayer == GameType.SPECTATOR) {
                state.lastPos = player.position()
                state.lastDimension = dimensionOf(player)
                continue
            }

            val pos = player.position()
            val dimension = dimensionOf(player)
            val delta = if (dimension == state.lastDimension) pos.distanceTo(state.lastPos) else Double.NaN
            if (delta > 0.0 && delta <= MAX_TICK_DISTANCE) {
                passport.distance.add(
                    Distance.bucketFor(
                        player.isFallFlying || player.abilities.flying,
                        player.vehicle != null,
                        player.isSwimming || player.isInWater,
                    ),
                    delta,
                )
                persistence.passports.markDirty(player.uuid)
            }
            state.lastPos = pos
            state.lastDimension = dimension

            if (tick % SAMPLE_INTERVAL_TICKS == 0L) {
                var changed = passport.dimensions.putIfAbsent(dimension, System.currentTimeMillis()) == null
                val biome = player.level().getBiome(player.blockPosition()).unwrapKey()
                    .map { it.identifier().toString() }
                    .orElse(null)
                if (biome != null && passport.biomes.putIfAbsent(biome, System.currentTimeMillis()) == null) {
                    changed = true
                }
                if (stampRegions(
                        passport,
                        RegionTracker.regionOf(player),
                        RegionsFeature.requireService(),
                        System.currentTimeMillis(),
                    )
                ) {
                    changed = true
                }
                if (changed) persistence.passports.markDirty(player.uuid)
            }
        }
        if (tick % FLUSH_INTERVAL_TICKS == 0L) persistence.passports.flushDirty()
    }

    private fun dimensionOf(player: ServerPlayer): String =
        player.level().dimension().identifier().toString()

    internal fun stampRegions(
        passport: Passport,
        region: eu.mctraveler.region.Region?,
        service: eu.mctraveler.region.RegionService,
        at: Long,
    ): Boolean {
        var changed = false
        region?.selfAndAncestors()?.forEach {
            val id = service.stableIdOf(it)
            if (passport.regions.putIfAbsent(id, at) == null) changed = true
        }
        return changed
    }
}
