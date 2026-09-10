package eu.mctraveler.passport

import eu.mctraveler.MCTraveler
import eu.mctraveler.region.RegionTracker
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import java.util.UUID

object PassportFeature {
    private const val FLUSH_INTERVAL_TICKS = 1200L
    private const val SAMPLE_INTERVAL_TICKS = 20L
    private const val MAX_TICK_DISTANCE = 100.0
    private const val FALL_STAMP_BLOCKS = 100.0
    private const val OVERWORLD_BIOME_TOTAL_UNAVAILABLE = 0

    private class State(player: ServerPlayer) {
        var lastPos: Vec3 = player.position()
        var lastDimension: String = dimensionOf(player)
        var fallPeak: Double = 0.0
    }

    private val states = HashMap<UUID, State>()
    private var overworldBiomeTotal = OVERWORLD_BIOME_TOTAL_UNAVAILABLE

    fun register() {
        ServerPlayerEvents.JOIN.register(::onJoin)
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            states.remove(handler.player.uuid)
            MCTraveler.persistence?.passports?.flushDirty()
        }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, source ->
            val player = entity as? ServerPlayer ?: return@register
            val persistence = MCTraveler.persistence ?: return@register
            val passport = persistence.passports.getOrCreate(
                player.uuid,
                persistence.players.firstJoin(player.uuid) ?: System.currentTimeMillis(),
            )
            passport.deaths++
            when (dimensionOf(player)) {
                "minecraft:the_nether" -> grantStamp(player, passport, "ashes_to_ashes")
                "minecraft:the_end" -> if (source.`is`(DamageTypes.FELL_OUT_OF_WORLD)) {
                    grantStamp(player, passport, "into_the_void")
                }
            }
            persistence.passports.markDirty(player.uuid)
            unlockStamps(player, passport)
        }
        ServerTickEvents.END_SERVER_TICK.register(::onEndServerTick)
        ServerLifecycleEvents.SERVER_STOPPING.register {
            MCTraveler.persistence?.passports?.flushDirty()
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { states.clear() }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            overworldBiomeTotal = try {
                server.overworld().chunkSource.generator.biomeSource.possibleBiomes().size
            } catch (_: Exception) {
                OVERWORLD_BIOME_TOTAL_UNAVAILABLE
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            overworldBiomeTotal = OVERWORLD_BIOME_TOTAL_UNAVAILABLE
            PassportEvents.clear()
            PostcardCommand.clear()
        }
        PassportCommand.register()
        PostcardCommand.register()
    }

    private fun onJoin(player: ServerPlayer) {
        val persistence = MCTraveler.persistence ?: return
        val passport = persistence.passports.getOrCreate(
            player.uuid,
            persistence.players.firstJoin(player.uuid) ?: System.currentTimeMillis(),
        )
        unlockStamps(player, passport)
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

            val fall = player.fallDistance
            if (fall >= state.fallPeak) {
                state.fallPeak = fall
            } else {
                // A fall only counts when it actually ended on the ground (or in
                // water) — not when a teleport reset fallDistance mid-air.
                val landed = !delta.isNaN() && delta <= MAX_TICK_DISTANCE &&
                    player.isAlive && (player.onGround() || player.isInWater)
                if (state.fallPeak >= FALL_STAMP_BLOCKS && landed) {
                    grantStamp(player, passport, "terminal_velocity")
                }
                state.fallPeak = fall
            }

            if (tick % SAMPLE_INTERVAL_TICKS == 0L) {
                var changed = passport.dimensions.putIfAbsent(dimension, System.currentTimeMillis()) == null
                val biome = biomeOf(player)
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
                val level = player.level()
                if (level.isThundering && level.isRainingAt(player.blockPosition())) {
                    grantStamp(player, passport, "storm_chaser")
                }
                unlockStamps(player, passport)
                if (changed) persistence.passports.markDirty(player.uuid)
            }
        }
        if (tick % FLUSH_INTERVAL_TICKS == 0L) persistence.passports.flushDirty()
    }

    internal fun dimensionOf(player: ServerPlayer): String =
        player.level().dimension().identifier().toString()

    internal fun biomeOf(player: ServerPlayer): String? =
        player.level().getBiome(player.blockPosition()).unwrapKey()
            .map { it.identifier().toString() }
            .orElse(null)

    internal fun overworldBiomeTotal(): Int = overworldBiomeTotal

    fun unlockStamps(player: ServerPlayer, passport: Passport) {
        val now = System.currentTimeMillis()
        val regionService = RegionsFeature.requireService()
        val unlocked = Stamps.evaluate(
            StampContext(
                passport = passport,
                embassies = PassportJson.embassyCount(passport, regionService),
                overworldBiomeTotal = overworldBiomeTotal,
                now = now,
                regions = passport.regions.keys.count { regionService.byStableId(it) != null },
                stat = { id -> player.stats.getValue(Stats.CUSTOM.get(id)) },
            ),
        )
        announceStamps(player, unlocked, now)
        if (unlocked.isNotEmpty()) MCTraveler.persistence?.passports?.markDirty(player.uuid)
    }

    private fun announceStamps(player: ServerPlayer, stamps: List<Stamp>, at: Long) {
        for (stamp in stamps) {
            player.sendSystemMessage(
                Paint.info(
                    "Stamp unlocked: ",
                    Paint.gold("${stamp.icon} ${stamp.title}"),
                    Paint.gray(" — ${stamp.description}"),
                ),
            )
            PassportEvents.record(
                StampEvent(
                    at = at,
                    player = player.gameProfile.name,
                    stamp = StampRef(stamp.id, stamp.title, stamp.description, stamp.icon),
                ),
            )
        }
    }

    private fun grantStamp(player: ServerPlayer, passport: Passport, stampId: String) {
        val now = System.currentTimeMillis()
        val stamp = Stamps.grant(passport, stampId, now) ?: return
        announceStamps(player, listOf(stamp), now)
        MCTraveler.persistence?.passports?.markDirty(player.uuid)
    }

    fun recordCrystalTrip(player: ServerPlayer) {
        val persistence = MCTraveler.persistence ?: return
        val passport = persistence.passports.getOrCreate(
            player.uuid,
            persistence.players.firstJoin(player.uuid) ?: System.currentTimeMillis(),
        )
        passport.crystalTrips++
        persistence.passports.markDirty(player.uuid)
        unlockStamps(player, passport)
    }

    fun recordRtpUse(player: ServerPlayer) {
        val persistence = MCTraveler.persistence ?: return
        val passport = persistence.passports.getOrCreate(
            player.uuid,
            persistence.players.firstJoin(player.uuid) ?: System.currentTimeMillis(),
        )
        passport.rtpUses++
        persistence.passports.markDirty(player.uuid)
        unlockStamps(player, passport)
    }

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
