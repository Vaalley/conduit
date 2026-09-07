package eu.mctraveler.gametest

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import java.util.Optional
import java.util.UUID
import net.minecraft.core.registries.Registries
import net.minecraft.network.Connection
import net.minecraft.network.DisconnectionDetails
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.players.NameAndId
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.TagValueInput

/**
 * Gametest players that traverse the real login/logout paths.
 *
 * [login] runs vanilla's own placement (`PlayerList.placeNewPlayer`, the same
 * call the network stack makes), so playerdata loading, spawn placement, and
 * every mod JOIN hook fire exactly as for a genuine connection — the vanilla
 * mock helpers (`GameTestHelper.makeMockServerPlayerInLevel`) use this same
 * recipe. [logout] disconnects through the connection handler, so playerdata
 * is saved and mod leave hooks fire; a later [login] with the same profile is
 * a genuine re-login against the saved data.
 */
object TestPlayers {

    fun login(server: MinecraftServer, name: String, uuid: UUID = UUID.randomUUID()): CapturingPlayer {
        val cookie = CommonListenerCookie.createInitial(GameProfile(uuid, name), false)
        val player = CapturingPlayer(server, cookie)
        // The configuration phase's spawn preparation (PrepareSpawnTask), in
        // miniature: restore saved playerdata — position, rotation, dimension,
        // the lot — before placement, exactly as a real re-login does.
        server.playerList.loadPlayerData(NameAndId(uuid, name)).ifPresent { tag ->
            player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag))
            val dimension = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.parse(tag.getStringOr("Dimension", "minecraft:overworld")),
            )
            player.setServerLevel(server.getLevel(dimension) ?: server.overworld())
        }
        val connection = Connection(PacketFlow.SERVERBOUND)
        EmbeddedChannel(connection)
        server.playerList.placeNewPlayer(connection, player, cookie)
        // Terrain is incidental to these tests (players get teleported into
        // unprepared chunks); invulnerability keeps suffocation/lava from
        // moving or killing them mid-assertion.
        player.isInvulnerable = true
        return player
    }

    fun logout(player: ServerPlayer) {
        player.connection.onDisconnect(DisconnectionDetails(Component.literal("gametest logout")))
    }
}

/**
 * Teleports the player and leaves them as a real session would be on arrival.
 * Vanilla holds a player mid-dimension-change — and shielded from all damage —
 * until their client acknowledges the move and says the world is loaded; a
 * test's embedded connection never sends either packet, so they are made here.
 */
fun ServerPlayer.arriveIn(level: ServerLevel, x: Double, y: Double, z: Double) {
    check(teleportTo(level, x, y, z, emptySet(), yRot, xRot, false)) {
        "teleport into ${level.dimension().identifier()} was rejected"
    }
    hasChangedDimension()
    connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
}

/** A [ServerPlayer] that records every system message it is sent, so tests can assert exact Portal wording. */
class CapturingPlayer(server: MinecraftServer, cookie: CommonListenerCookie) :
    ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation()) {

    val messages = mutableListOf<Component>()

    override fun sendSystemMessage(message: Component) {
        messages += message
        super.sendSystemMessage(message)
    }
}

/**
 * One styled run of a message as the player sees it: consecutive text with its
 * effective color (the serialized name, e.g. `"green"`) and boldness — the two
 * style channels the Portal's message language uses.
 */
data class TextRun(val text: String, val color: String?, val bold: Boolean = false)

/** The message flattened to its player-visible [TextRun]s, styles inherited as rendered. */
fun Component.textRuns(): List<TextRun> {
    val runs = mutableListOf<TextRun>()
    visit(
        FormattedText.StyledContentConsumer<Unit> { style, text ->
            if (text.isNotEmpty()) {
                runs += TextRun(text, style.color?.serialize(), style.isBold)
            }
            Optional.empty()
        },
        Style.EMPTY,
    )
    return runs
}
