package eu.mctraveler.gametest

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import java.util.UUID
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3

/**
 * A mock online player that records every system message it is sent, so
 * command gametests can assert the exact text and styling a real player
 * would see. Joined through the real login path ([join]), so it is a full
 * member of the player list.
 */
class MessageCapturingPlayer(
    server: MinecraftServer,
    level: ServerLevel,
    profile: GameProfile,
    clientInformation: ClientInformation,
) : ServerPlayer(server, level, profile, clientInformation) {

    val messages = mutableListOf<Component>()

    override fun sendSystemMessage(message: Component) {
        messages.add(message)
    }

    override fun sendSystemMessage(message: Component, isActionBar: Boolean) {
        messages.add(message)
    }

    companion object {
        /**
         * Joins a fresh mock player named [name] to the test server, the way
         * vanilla's own gametest mock does: a real ServerPlayer behind an
         * embedded (packet-discarding) connection, placed via the player list.
         */
        fun join(helper: GameTestHelper, name: String): MessageCapturingPlayer {
            val server = helper.level.server
            val cookie = CommonListenerCookie.createInitial(GameProfile(UUID.randomUUID(), name), false)
            val player = MessageCapturingPlayer(server, helper.level, cookie.gameProfile(), cookie.clientInformation())
            val connection = Connection(PacketFlow.SERVERBOUND)
            EmbeddedChannel(connection)
            server.playerList.placeNewPlayer(connection, player, cookie)
            // Ack the world load as a real client does; until then the server
            // treats the session as still loading and ignores the interaction
            // packets protection gametests drive.
            player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
            // MCTraveler is a survival server; the gametest server's default is
            // creative, which quietly changes what items and blocks do (nothing
            // is consumed, buckets are not filled, every block breaks at once).
            player.setGameMode(GameType.SURVIVAL)
            return player
        }
    }

    /** Stands the player at the structure-relative position. */
    fun standAt(helper: GameTestHelper, x: Double, y: Double, z: Double) {
        val absolute = helper.absoluteVec(Vec3(x, y, z))
        setPos(absolute.x, absolute.y, absolute.z)
    }

    /** Runs a command (no leading slash) as this player. */
    fun runCommand(command: String) {
        level().server.commands.performPrefixedCommand(createCommandSourceStack(), command)
    }

    /** Makes this player an Admin: a vanilla operator, via the real ops list. */
    fun makeAdmin() {
        level().server.playerList.op(nameAndId())
    }

    /** Leaves the server (test cleanup). */
    fun leave() {
        level().server.playerList.remove(this)
    }
}
