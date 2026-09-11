package eu.mctraveler.vanish

import com.mojang.brigadier.Command
import eu.mctraveler.chat.ChatFeature
import eu.mctraveler.lodeway.LodewayFeature
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.util.EnumSet
import java.util.UUID
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.Commands
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType

/**
 * `/vanish` (issue #47): an admin drops off the server as far as everyone
 * without operator status is concerned — the leave line goes out, the tab entry
 * and the body disappear, `/status` and the server-list sample stop counting
 * them — while they float around in Spectator watching. `/vanish` again brings
 * them back with a join line, as if they had just reconnected.
 *
 * Other admins are never fooled: they keep seeing the vanished admin, in the
 * tab and in the world, and get a plain "went into vanish mode" line rather
 * than the fake leave.
 *
 * State is per session: a disconnect clears it (the player is already "gone" to
 * non-admins, so only admins get the real leave line — see [ChatFeature]), and
 * a fresh login comes back visible.
 */
object VanishFeature {

    /** Vanished players, each mapped to the game mode to restore on unvanish. */
    private val vanished = HashMap<UUID, Session>()

    private data class Session(val gameType: GameType, val playerName: String)

    /** Whether [player] is currently vanished. */
    @JvmStatic
    fun isVanished(player: ServerPlayer): Boolean = player.uuid in vanished

    /** Whether anyone is vanished right now — the per-packet scan's early-out. */
    @JvmStatic
    fun anyVanished(): Boolean = vanished.isNotEmpty()

    /**
     * Whether [target] should be hidden from [viewer] right now — vanished, and
     * [viewer] is neither an admin nor [target] themselves.
     */
    @JvmStatic
    fun isHiddenFrom(target: ServerPlayer, viewer: ServerPlayer): Boolean =
        target.uuid in vanished && target !== viewer && !RegionsFeature.isAdmin(viewer)

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("vanish")
                    // Hidden from the command tree for non-admins — a stranger never
                    // sees it in tab-completion or the command list, not just a
                    // refusal on running it. Non-player sources (console) keep it.
                    .requires { source -> source.player?.let(RegionsFeature::isAdmin) ?: true }
                    .executes { ctx ->
                        toggle(ctx.source.playerOrException)
                        Command.SINGLE_SUCCESS
                    },
            )
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            vanished.remove(handler.player.uuid)?.let { session ->
                LodewayFeature.setPlayerInvisible(session.playerName, false)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            vanished.values.forEach { session ->
                LodewayFeature.setPlayerInvisible(session.playerName, false)
            }
            vanished.clear()
        }
    }

    private fun toggle(player: ServerPlayer) {
        RegionsFeature.adminGate(player)?.let {
            player.sendSystemMessage(it, false)
            return
        }
        if (isVanished(player)) leave(player) else enter(player)
    }

    private fun enter(player: ServerPlayer) {
        val server = player.level().server
        val name = player.gameProfile.name
        vanished[player.uuid] = Session(player.gameMode.gameModeForPlayer, name)
        player.setGameMode(GameType.SPECTATOR)
        LodewayFeature.setPlayerInvisible(name, true)

        for (viewer in nonAdminViewersOf(server, player)) {
            viewer.connection.send(ClientboundPlayerInfoRemovePacket(listOf(player.uuid)))
            viewer.connection.send(ClientboundRemoveEntitiesPacket(player.id))
        }

        for (viewer in server.playerList.players) {
            if (viewer === player) continue
            viewer.sendSystemMessage(
                if (RegionsFeature.isAdmin(viewer)) adminLine(name, "went into vanish mode")
                else ChatFeature.leaveLine(name),
                false,
            )
        }
        player.sendSystemMessage(Paint.aqua("You vanished — run ", Paint.aqua.bold("/vanish"), " to return."), false)
    }

    private fun leave(player: ServerPlayer) {
        val server = player.level().server
        val session = vanished.remove(player.uuid)
        player.setGameMode(session?.gameType ?: GameType.SURVIVAL)
        LodewayFeature.setPlayerInvisible(session?.playerName ?: player.gameProfile.name, false)

        for (viewer in nonAdminViewersOf(server, player)) {
            viewer.connection.send(
                ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                    ),
                    listOf(player),
                ),
            )
        }
        // The body re-tracks to non-admins on the next entity-tracking tick,
        // once VanishTrackingMixin stops hiding it.

        val name = player.gameProfile.name
        for (viewer in server.playerList.players) {
            if (viewer === player) continue
            viewer.sendSystemMessage(
                if (RegionsFeature.isAdmin(viewer)) adminLine(name, "left vanish mode")
                else ChatFeature.joinLineFor(player),
                false,
            )
        }
        player.sendSystemMessage(Paint.aqua("You are visible again."), false)
    }

    private fun nonAdminViewersOf(server: MinecraftServer, player: ServerPlayer): List<ServerPlayer> =
        server.playerList.players.filter { it !== player && !RegionsFeature.isAdmin(it) }

    private fun adminLine(name: String, what: String) = Paint.gray(
        Paint.darkGray("["), Paint.aqua("V"), Paint.darkGray("]"), " ", Paint.aqua(name), " ", what,
    )
}
