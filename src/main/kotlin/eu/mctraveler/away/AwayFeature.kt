package eu.mctraveler.away

import com.mojang.brigadier.Command
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.minecraft.commands.Commands
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.roundToLong

/**
 * The away system (Portal: features/AwayFeature.ts; spec stories 16–18).
 *
 * `/away` marks the sender away immediately; five idle minutes mark a player away
 * automatically; any interaction brings them back. Every transition is broadcast to
 * all players as a gray line with the username in green. State is in-memory only.
 *
 * All timing is in server ticks (spec: never wall-clock), which is what lets
 * gametests fast-forward a player's clock instead of waiting.
 */
object AwayFeature {

    /** Five idle minutes, in server ticks, before a player is automatically marked away. */
    const val AWAY_TIMEOUT_TICKS: Long = 5 * 60 * 20

    /** The idle checker's cadence (the Portal checked every 5 s). */
    const val CHECK_INTERVAL_TICKS: Long = 100

    /** How long `/away` is refused after returning from away (the Portal's 3 s). */
    const val RETURN_COOLDOWN_TICKS: Long = 60

    private class State(player: ServerPlayer, tick: Long) {
        var lastInteractionTick = tick
        var lastPosition: Vec3 = player.position()
        var lastYRot = player.yRot
        var lastXRot = player.xRot
        var away = false

        /** Set when the player returns from away; `/away` is refused while it is fresh. */
        var cooldownStartTick: Long? = null
    }

    private val states = HashMap<UUID, State>()

    /**
     * Gametest affordance: ages [player]'s away clock by [ticks] server ticks, as if
     * that much time had passed without them interacting. Scoped to one player so
     * concurrently running tests cannot warp each other's timing.
     */
    fun fastForward(player: ServerPlayer, ticks: Long) {
        val state = states[player.uuid] ?: return
        state.lastInteractionTick -= ticks
        state.cooldownStartTick = state.cooldownStartTick?.minus(ticks)
    }

    fun register() {
        ServerPlayerEvents.JOIN.register(::onInteraction)
        ServerPlayerEvents.LEAVE.register { player -> states.remove(player.uuid) }
        // Away state is in-memory and per-session; never let it leak across restarts.
        ServerLifecycleEvents.SERVER_STOPPED.register { states.clear() }
        ServerMessageEvents.CHAT_MESSAGE.register { _, sender, _ -> onInteraction(sender) }
        PlayerBlockBreakEvents.AFTER.register { _, player, _, _, _ ->
            (player as? ServerPlayer)?.let(::onInteraction)
        }
        UseBlockCallback.EVENT.register { player, _, _, _ ->
            (player as? ServerPlayer)?.let(::onInteraction)
            InteractionResult.PASS
        }
        UseItemCallback.EVENT.register { player, _, _ ->
            (player as? ServerPlayer)?.let(::onInteraction)
            InteractionResult.PASS
        }
        ServerTickEvents.END_SERVER_TICK.register(::onEndServerTick)
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("away").executes { context ->
                    onAwayCommand(context.source.playerOrException)
                },
            )
        }
    }

    /** Called from CommandsMixin: every player-issued command is an interaction. */
    fun onPlayerCommand(player: ServerPlayer) = onInteraction(player)

    /** Any interaction resets the idle clock and brings an away player back. */
    private fun onInteraction(player: ServerPlayer) {
        val tick = currentTick(player)
        val state = states.getOrPut(player.uuid) { State(player, tick) }
        state.lastInteractionTick = tick
        state.lastPosition = player.position()
        state.lastYRot = player.yRot
        state.lastXRot = player.xRot
        if (state.away) {
            state.away = false
            state.cooldownStartTick = tick
            broadcastTransition(player, " is no longer away")
        }
    }

    private fun onEndServerTick(server: MinecraftServer) {
        val tick = server.tickCount.toLong()
        // Movement is client-authoritative, so there is no server event for it;
        // detect it as a position/look change since the last tick.
        for (player in server.playerList.players) {
            val state = states[player.uuid] ?: continue
            if (player.position() != state.lastPosition ||
                player.yRot != state.lastYRot ||
                player.xRot != state.lastXRot
            ) {
                onInteraction(player)
            }
        }
        if (tick % CHECK_INTERVAL_TICKS != 0L) return
        for (player in server.playerList.players) {
            val state = states[player.uuid] ?: continue
            if (!state.away && tick - state.lastInteractionTick > AWAY_TIMEOUT_TICKS) {
                state.away = true
                broadcastTransition(player, " is now away")
            }
        }
    }

    private fun onAwayCommand(player: ServerPlayer): Int {
        val tick = currentTick(player)
        val state = states.getOrPut(player.uuid) { State(player, tick) }
        // The command itself already counted as an interaction (CommandsMixin), so a
        // player typing /away while away has just returned and lands in this window.
        // The Portal stayed silent when exactly 3.0 s remained; we always show the
        // error (deviation recorded in the ticket).
        val cooldownStart = state.cooldownStartTick
        if (cooldownStart != null) {
            val remaining = RETURN_COOLDOWN_TICKS - (tick - cooldownStart)
            if (remaining > 0) {
                player.sendSystemMessage(
                    Paint.error(
                        "You cannot use /away again for another ",
                        Paint.red(formatSeconds(remaining)),
                        " seconds yet",
                    ),
                )
                return 0
            }
        }
        if (!state.away) {
            state.away = true
            broadcastTransition(player, " is now away")
        }
        return Command.SINGLE_SUCCESS
    }

    /**
     * Remaining ticks rendered as seconds at the Portal's 0.1 s precision, in its
     * JS number formatting: whole values drop the decimal ("3", "2.9", "0.5").
     */
    private fun formatSeconds(remainingTicks: Long): String {
        val tenths = (remainingTicks / 2.0).roundToLong()
        return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    }

    /** The Portal's exact transition line: gray, with the username in green. */
    private fun broadcastTransition(player: ServerPlayer, suffix: String) {
        val message = Paint.gray(Paint.green(player.gameProfile.name), suffix)
        player.level().server.playerList.broadcastSystemMessage(message, false)
    }

    private fun currentTick(player: ServerPlayer): Long = player.level().server.tickCount.toLong()
}
