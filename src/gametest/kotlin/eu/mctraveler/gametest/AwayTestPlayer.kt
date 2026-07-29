package eu.mctraveler.gametest

import com.mojang.authlib.GameProfile
import net.minecraft.core.UUIDUtil
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.DisconnectionDetails
import net.minecraft.network.PacketListener
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie

/**
 * A headless player for gametests, connected through the real login path
 * ([net.minecraft.server.players.PlayerList.placeNewPlayer]) so that join/disconnect
 * events, player-list membership, and broadcasts all behave as for a real client.
 *
 * Every system message the player is shown is recorded in [received] — the
 * "what did this player see" seam the behaviour tests assert on.
 */
class AwayTestPlayer private constructor(
    level: ServerLevel,
    profile: GameProfile,
) : ServerPlayer(level.server, level, profile, ClientInformation.createDefault()) {

    val received = mutableListOf<Component>()

    // The 1-arg overload (used by command sources) delegates here, so this
    // single override captures every system message sent to the player.
    override fun sendSystemMessage(message: Component, overlay: Boolean) {
        received += message
    }

    /** Runs a command exactly as if this player had typed it (the mod's command seam). */
    fun runCommand(command: String) {
        val commands = level().server.commands
        commands.performCommand(commands.dispatcher.parse(command, createCommandSourceStack()), command)
    }

    /** Leaves the server through the real path, firing the same events a client quit would. */
    fun leave() {
        connection.onDisconnect(DisconnectionDetails(Component.literal("gametest finished")))
    }

    companion object {
        /** Joins a fresh player named [name] to the test server. */
        fun join(helper: GameTestHelper, name: String): AwayTestPlayer {
            val level = helper.level
            val profile = GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name)
            val player = AwayTestPlayer(level, profile)
            level.server.playerList.placeNewPlayer(
                HeadlessConnection(),
                player,
                CommonListenerCookie(profile, 0, player.clientInformation(), false),
            )
            return player
        }
    }

    /** A connection with no netty channel: packets queue harmlessly, nothing is transmitted. */
    private class HeadlessConnection : Connection(PacketFlow.SERVERBOUND) {
        // The vanilla implementation reconfigures the (nonexistent) channel's pipeline.
        override fun <T : PacketListener> setupInboundProtocol(protocolInfo: ProtocolInfo<T>, packetListener: T) {}
    }
}
