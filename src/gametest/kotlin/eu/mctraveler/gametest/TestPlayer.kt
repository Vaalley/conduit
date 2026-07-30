package eu.mctraveler.gametest

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import java.time.Instant
import java.util.BitSet
import java.util.UUID
import net.minecraft.network.Connection
import net.minecraft.network.DisconnectionDetails
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.LastSeenMessages
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie

/**
 * A headless player joined through the real login path ([net.minecraft.server.players.PlayerList.placeNewPlayer]),
 * with its clientbound packets captured — "what this player's client was shown" is the
 * assertion seam for every chat behaviour.
 */
class TestPlayer private constructor(
    val player: ServerPlayer,
    private val server: MinecraftServer,
    private val connection: Connection,
    private val channel: EmbeddedChannel,
) {
    val name: String get() = player.gameProfile.name

    /** Everything sent to this player's client, with bundle packets flattened. */
    fun clientboundPackets(): List<Packet<*>> = channel.outboundMessages().flatMap { message ->
        when (message) {
            is ClientboundBundlePacket -> message.subPackets().toList()
            is Packet<*> -> listOf(message)
            else -> emptyList()
        }
    }

    /** Chat-line system messages this player saw (action-bar overlays excluded). */
    fun systemMessages(): List<Component> = clientboundPackets()
        .filterIsInstance<ClientboundSystemChatPacket>()
        .filterNot { it.overlay() }
        .map { it.content() }

    /** Signed-chat lines this player saw. */
    fun chatPackets(): List<ClientboundPlayerChatPacket> =
        clientboundPackets().filterIsInstance<ClientboundPlayerChatPacket>()

    /** Server-authored player-voiced lines (e.g. emote commands) this player saw. */
    fun disguisedChatPackets(): List<ClientboundDisguisedChatPacket> =
        clientboundPackets().filterIsInstance<ClientboundDisguisedChatPacket>()

    /** Sends a chat line exactly as a vanilla client would: a serverbound chat packet. */
    fun chat(message: String) {
        player.connection.handleChat(
            ServerboundChatPacket(
                message,
                Instant.now(),
                0L,
                null,
                LastSeenMessages.Update(0, BitSet(), LastSeenMessages.Update.IGNORE_CHECKSUM),
            ),
        )
    }

    /** Runs a command as this player, with or without the leading slash. */
    fun runCommand(command: String) {
        server.commands.performPrefixedCommand(player.createCommandSourceStack(), command)
    }

    fun moveTo(level: ServerLevel, x: Double, y: Double, z: Double) {
        player.teleportTo(level, x, y, z, setOf(), 0f, 0f, false)
        // Model the client finishing the move: the dimension change completes and the
        // reloaded world is acked, as a real client would do. (Until then the server
        // shields the player from all damage.)
        player.hasChangedDimension()
        player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
    }

    /**
     * Drops the connection the way the network layer does: close the channel, then run
     * the disconnection handling the connection listener would (which is also where
     * Fabric's disconnect event fires).
     */
    fun disconnect() {
        connection.disconnect(DisconnectionDetails(Component.literal("Test player leaving")))
        connection.handleDisconnection()
    }

    companion object {
        /** Joins a fresh player named [name] to the server through the real login path. */
        fun join(server: MinecraftServer, name: String): TestPlayer =
            joinAs(server, GameProfile(UUID.randomUUID(), name))

        /**
         * Joins [profile] through the real login path. Use when the identity itself
         * is what the test is about — an aliased uuid, say — rather than just a name.
         */
        fun joinAs(server: MinecraftServer, profile: GameProfile): TestPlayer {
            val cookie = CommonListenerCookie.createInitial(profile, false)
            val connection = Connection(PacketFlow.SERVERBOUND)
            val channel = EmbeddedChannel(connection)
            val player = ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation())
            server.playerList.placeNewPlayer(connection, player, cookie)
            // Ack world load like a real client; until then the server treats the player
            // as still loading (and, e.g., invulnerable to everything).
            player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
            return TestPlayer(player, server, connection, channel)
        }
    }
}
