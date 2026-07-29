package eu.mctraveler.region

import java.util.UUID
import kotlin.math.floor
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * Which region each player is standing in, and the sidebar that follows from it.
 *
 * The answer is recomputed from every player's live position once per server
 * tick, so entering and leaving a region are noticed however the player got
 * there — walking, teleporting, or a region appearing around them. [refresh]
 * does one player on demand: the seam for arrivals worth reacting to within the
 * same tick (ticket 14's teleport- and portal-aware tracking hangs there), and
 * [regionOf] answers the same question without the sidebar's bookkeeping.
 */
object RegionTracker {

    /** The region each player's sidebar is currently drawn for. */
    private val inside = HashMap<UUID, Region>()

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            for (player in server.playerList.players) refresh(player)
        }
        // A session starts with nothing drawn and nothing tracked: a fresh
        // connection's client knows no objective, whatever the last one left.
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> forget(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> forget(handler.player) }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            inside.clear()
            RegionScoreboard.forgetAll()
        }
    }

    /** The region [player] is standing in right now, from their live position. */
    fun regionOf(player: ServerPlayer): Region? {
        val service = RegionsFeature.service ?: return null
        val pos = player.position()
        return service.regionAt(
            RegionWorlds.legacyName(player.level().dimension()),
            floor(pos.x).toInt(),
            floor(pos.y).toInt(),
            floor(pos.z).toInt(),
        )
    }

    /** Brings [player]'s sidebar in line with where they now are. */
    fun refresh(player: ServerPlayer) {
        val region = regionOf(player)
        if (region === inside[player.uuid]) return
        if (region == null) {
            inside.remove(player.uuid)
            RegionScoreboard.hide(player)
        } else {
            inside[player.uuid] = region
            RegionScoreboard.draw(player, region)
        }
    }

    /** Redraws [region] for everyone inside it — what its sidebar says changed. */
    fun redraw(server: MinecraftServer, region: Region) {
        for (player in playersIn(server, region)) RegionScoreboard.draw(player, region)
    }

    /** Takes [region]'s sidebar off the screen of everyone inside it — it is going away. */
    fun clear(server: MinecraftServer, region: Region) {
        for (player in playersIn(server, region)) {
            inside.remove(player.uuid)
            RegionScoreboard.hide(player)
        }
    }

    /** Forgets a player's tracked region and board when their session ends. */
    fun forget(player: ServerPlayer) {
        inside.remove(player.uuid)
        RegionScoreboard.forget(player.uuid)
    }

    private fun playersIn(server: MinecraftServer, region: Region): List<ServerPlayer> =
        server.playerList.players.filter { inside[it.uuid] === region }
}
