package eu.mctraveler.region

import eu.mctraveler.reloadable
import java.util.UUID
import kotlin.math.floor
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType

/**
 * Which region each player is standing in, the sidebar that follows from it,
 * and the client-facing Adventure mode used while a survival player stands in
 * land they cannot modify.
 *
 * The answer is recomputed from every player's live position once per server
 * tick, so entering and leaving a region are noticed however the player got
 * there — walking, teleporting, or a region appearing around them. [refresh]
 * does one player on demand: the seam arrivals worth reacting to within the
 * same tick hang off portals, Travel, respawns, and membership changes.
 */
object RegionTracker {

    /** The region each player's sidebar is currently drawn for. */
    private val inside = HashMap<UUID, Region>()

    /** Players whose Survival -> Adventure transition this tracker owns. */
    private val forcedAdventure = HashSet<UUID>()

    fun register() {
        ServerTickEvents.END_SERVER_TICK.reloadable.register { server ->
            for (player in server.playerList.players) refresh(player)
        }
        // Arrivals no movement packet announces (deviation 9 — the Portal only
        // ever saw players walk): a nether or end portal, a Travel, any
        // cross-World teleport, and coming back from a death. The sweep above
        // would catch each of them by the end of the same tick; refreshing
        // here puts the sidebar up in the same breath as the arrival.
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.reloadable.register { player, _, _ -> refresh(player) }
        ServerPlayerEvents.AFTER_RESPAWN.reloadable.register { _, player, _ -> refresh(player) }
        ServerPlayerEvents.LEAVE.reloadable.register(::forget)
        // A session starts with nothing drawn and nothing tracked: a fresh
        // connection's client knows no objective, whatever the last one left.
        ServerPlayConnectionEvents.JOIN.reloadable.register { handler, _, _ ->
            forget(handler.player)
            refresh(handler.player)
        }
        ServerPlayConnectionEvents.DISCONNECT.reloadable.register { handler, _ -> forget(handler.player) }
        // Do not persist a tracker-owned Adventure mode into player data.
        ServerLifecycleEvents.SERVER_STOPPING.reloadable.register { server ->
            for (player in server.playerList.players) restoreGameMode(player)
        }
        ServerLifecycleEvents.SERVER_STOPPED.reloadable.register {
            inside.clear()
            forcedAdventure.clear()
            RegionScoreboard.forgetAll()
        }
    }

    /** The region [player] is standing in right now, from their live position. */
    fun regionOf(player: ServerPlayer): Region? {
        val pos = player.position()
        return RegionsFeature.regionAt(
            RegionWorlds.legacyName(player.level().dimension()),
            floor(pos.x).toInt(),
            floor(pos.y).toInt(),
            floor(pos.z).toInt(),
        )
    }

    /** Brings [player]'s mode and sidebar in line with where they now are. */
    fun refresh(player: ServerPlayer) {
        val region = regionOf(player)
        updateGameMode(player, region)
        if (region === inside[player.uuid]) return
        if (region == null) {
            inside.remove(player.uuid)
            RegionScoreboard.hide(player)
        } else {
            inside[player.uuid] = region
            RegionScoreboard.draw(player, region)
        }
    }

    /** Redraws [region] and reapplies its live membership state to every occupant. */
    fun redraw(server: MinecraftServer, region: Region) {
        for (player in playersIn(server, region)) {
            updateGameMode(player, region)
            RegionScoreboard.draw(player, region)
        }
    }

    /** Recomputes every live player's mode and sidebar after the region tree changed. */
    fun afterRemoval(server: MinecraftServer) {
        for (player in server.playerList.players) refresh(player)
    }

    /**
     * A region's footprint grew under everyone's feet (`/rg extend`): the same
     * full recompute as a removal, so anyone the region now covers gets its
     * sidebar and Adventure mode in the same breath rather than next tick.
     */
    fun afterBoundsChange(server: MinecraftServer) = afterRemoval(server)

    /** Forgets a player's region state and restores any mode transition we own. */
    fun forget(player: ServerPlayer) {
        restoreGameMode(player)
        inside.remove(player.uuid)
        RegionScoreboard.forget(player.uuid)
    }

    /**
     * Only Survival is transformed. Creative and Spectator remain deliberate
     * administrative modes, and an Adventure mode we did not set is left alone.
     */
    private fun updateGameMode(player: ServerPlayer, region: Region?) {
        val uuid = player.uuid
        val mode = player.gameMode.gameModeForPlayer
        if (uuid in forcedAdventure && mode != GameType.ADVENTURE) {
            forcedAdventure.remove(uuid)
        }

        val isForeign = region != null && !RegionProtection.canModifyRegion(player, region)
        if (isForeign) {
            if (mode == GameType.SURVIVAL) {
                player.setGameMode(GameType.ADVENTURE)
                forcedAdventure.add(uuid)
            }
        } else {
            restoreGameMode(player)
        }
    }

    /** Restores Survival only when this tracker performed the transition. */
    private fun restoreGameMode(player: ServerPlayer) {
        if (!forcedAdventure.remove(player.uuid)) return
        if (player.gameMode.gameModeForPlayer == GameType.ADVENTURE) {
            player.setGameMode(GameType.SURVIVAL)
        }
    }

    private fun playersIn(server: MinecraftServer, region: Region): List<ServerPlayer> =
        server.playerList.players.filter { inside[it.uuid] === region }
}
