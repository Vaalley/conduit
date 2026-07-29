package eu.mctraveler.gametest

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import java.util.UUID
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie

/**
 * A fake player joined through the real login path (`PlayerList.placeNewPlayer`) over an
 * in-memory netty channel — the same trick vanilla's `GameTestHelper.makeMockServerPlayerInLevel`
 * uses, plus a chosen identity and capture of the packets a real client would receive.
 *
 * Being placed via the real login path, the player is in the server's player list: name
 * lookups, tab-complete suggestions, and broadcasts all see it, and [disconnect] runs the
 * real disconnect flow (firing the events features clean up on).
 */
class FakePlayer private constructor(
    private val server: MinecraftServer,
    val player: ServerPlayer,
    private val connection: Connection,
    private val channel: EmbeddedChannel,
) {
    val name: String get() = player.gameProfile.name

    /** Runs a slash command exactly as if this player typed it. */
    fun runCommand(command: String) {
        server.commands.performPrefixedCommand(player.createCommandSourceStack(), command)
    }

    /**
     * The chat lines this player's client would have rendered since the last drain, oldest
     * first: system-chat packets on the wire, minus action-bar overlays.
     */
    fun receivedChatLines(): List<Component> {
        val lines = mutableListOf<Component>()
        while (true) {
            val packet = channel.outboundMessages().poll() ?: break
            if (packet is ClientboundSystemChatPacket && !packet.overlay()) {
                lines += packet.content()
            }
        }
        return lines
    }

    /** Drops everything received so far (e.g. join-time noise) before the action under test. */
    fun clearReceived() {
        channel.outboundMessages().clear()
    }

    private var disconnected = false

    /**
     * Disconnects through the real path, as if the client's connection dropped: closing the
     * channel fires the netty-side teardown (where Fabric's disconnect events hang), and
     * [Connection.handleDisconnection] then runs the vanilla player-removal that the server's
     * network tick would. Idempotent.
     */
    fun disconnect() {
        if (disconnected) return
        disconnected = true
        connection.disconnect(Component.literal("fake player left"))
        connection.handleDisconnection()
    }

    companion object {
        /** Joins a fake player to the server. A fixed [uuid] lets a test rejoin the same identity. */
        fun join(server: MinecraftServer, name: String, uuid: UUID = UUID.randomUUID()): FakePlayer {
            val cookie = CommonListenerCookie.createInitial(GameProfile(uuid, name), false)
            val player = ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation())
            val connection = Connection(PacketFlow.SERVERBOUND)
            val channel = EmbeddedChannel(connection)
            server.playerList.placeNewPlayer(connection, player, cookie)
            return FakePlayer(server, player, connection, channel)
        }
    }
}

/**
 * Joins one fake player per name, drops the join-time noise from all of them, runs [block],
 * and always disconnects them again so tests leave no one behind.
 */
fun GameTestHelper.withFakePlayers(vararg names: String, block: (List<FakePlayer>) -> Unit) {
    val server = level.server
    val players = names.map { FakePlayer.join(server, it) }
    try {
        players.forEach(FakePlayer::clearReceived)
        block(players)
    } finally {
        players.forEach(FakePlayer::disconnect)
    }
}
