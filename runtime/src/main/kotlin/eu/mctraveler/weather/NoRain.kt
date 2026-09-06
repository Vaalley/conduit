package eu.mctraveler.weather

import com.mojang.brigadier.Command
import eu.mctraveler.reloadable
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.commands.Commands
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Per-player weather presentation for `/norain`.
 *
 * The command changes no [net.minecraft.server.level.ServerLevel] weather state: it only
 * substitutes the weather packets sent to a player who opted in. The outgoing packet seam
 * is the only place that knows both the packet and its recipient, so
 * [eu.mctraveler.mixin.OutboundPacketMixin] delegates the actual substitution to this
 * object. State is in-memory and session-scoped.
 */
object NoRain {
    private val hiddenViewers = ConcurrentHashMap.newKeySet<UUID>()

    fun register() {
        ServerPlayerEvents.LEAVE.reloadable.register { player -> hiddenViewers.remove(player.uuid) }
        ServerLifecycleEvents.SERVER_STOPPED.reloadable.register { hiddenViewers.clear() }
        CommandRegistrationCallback.EVENT.reloadable.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("norain").executes { context ->
                    toggle(context.source.playerOrException)
                },
            )
        }
    }

    /**
     * [packet] as seen by [viewer]: weather game events become clear-sky events only for a
     * viewer who has enabled `/norain`; all other packets and viewers pass through intact.
     */
    @JvmStatic
    fun forViewer(viewer: ServerPlayer, packet: Packet<*>): Packet<*> =
        forViewer(viewer.uuid, packet)

    /**
     * UUID-based form of [forViewer] kept free of a live server so packet decisions can be
     * tested without constructing a [ServerPlayer].
     */
    internal fun forViewer(viewer: UUID, packet: Packet<*>): Packet<*> {
        if (viewer !in hiddenViewers || packet !is ClientboundGameEventPacket) return packet
        return when (packet.event) {
            ClientboundGameEventPacket.START_RAINING -> ClientboundGameEventPacket(
                ClientboundGameEventPacket.STOP_RAINING,
                0f,
            )

            ClientboundGameEventPacket.STOP_RAINING -> packet

            ClientboundGameEventPacket.RAIN_LEVEL_CHANGE -> ClientboundGameEventPacket(
                ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
                0f,
            )

            ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE -> ClientboundGameEventPacket(
                ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
                0f,
            )

            else -> packet
        }
    }

    internal fun setHidden(viewer: UUID, hidden: Boolean) {
        if (hidden) {
            hiddenViewers += viewer
        } else {
            hiddenViewers -= viewer
        }
    }

    private fun toggle(player: ServerPlayer): Int {
        val hidden = player.uuid !in hiddenViewers
        setHidden(player.uuid, hidden)
        if (hidden) {
            player.connection.send(
                ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0f),
            )
            player.connection.send(
                ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0f),
            )
            player.connection.send(
                ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0f),
            )
            player.sendSystemMessage(Paint.success("Rain is now hidden for you only"))
        } else {
            sendCurrentWeather(player)
            player.sendSystemMessage(Paint.info("Rain is visible again"))
        }
        return Command.SINGLE_SUCCESS
    }

    /**
     * Restores only the current weather view for [player].
     *
     * The vanilla `PlayerList.sendLevelInfo` helper is intentionally avoided here because it
     * also resends unrelated level state such as the world border, spawn position, time,
     * chunk-load state, and tick-rate state. These are the narrow weather packets vanilla uses,
     * sent directly through this player's connection.
     */
    private fun sendCurrentWeather(player: ServerPlayer) {
        val level = player.level()
        if (level.isRaining) {
            player.connection.send(
                ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0f),
            )
        }
        player.connection.send(
            ClientboundGameEventPacket(
                ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
                level.getRainLevel(1f),
            ),
        )
        player.connection.send(
            ClientboundGameEventPacket(
                ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
                level.getThunderLevel(1f),
            ),
        )
    }
}
