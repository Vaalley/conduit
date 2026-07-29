package eu.mctraveler.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.ChatFormatting
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.ChatTypeDecoration
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * MCTraveler's chat voice (ticket 06): join/leave lines, global green-name chat,
 * death broadcasts, and the /shrug + /tableflip emotes — asserted at the primary
 * seam, the packets each player's client is shown.
 *
 * Expected messages are constructed with the raw component API (never the Paint DSL)
 * so the expectations stay independent of the implementation's text machinery.
 */
class ChatGameTest {

    @GameTest
    fun joinBroadcastsPortalLineAndSuppressesVanilla(helper: GameTestHelper) {
        val server = helper.level.server
        val observer = TestPlayer.join(server, "JoinCarol")
        val joiner = TestPlayer.join(server, "JoinDave")
        val expected = portalJoinLine("JoinDave")
        helper.succeedWhen {
            if (observer.systemMessages().any { it.mentionsVanillaPresenceOf("JoinDave") }) {
                throw helper.assertionException("vanilla join message for JoinDave was not suppressed")
            }
            if (observer.systemMessages().none { it == expected }) {
                throw helper.assertionException("observer has not seen the Portal join line for JoinDave")
            }
            if (joiner.systemMessages().none { it == expected }) {
                throw helper.assertionException("the joining player did not see their own join line")
            }
        }
    }

    @GameTest
    fun chatIsGreenNameFormattedSignedAndCrossesDimensions(helper: GameTestHelper) {
        val server = helper.level.server
        val speaker = TestPlayer.join(server, "ChatAlice")
        val listener = TestPlayer.join(server, "ChatBob")
        listener.moveTo(server.getLevel(Level.NETHER)!!, 0.0, 100.0, 0.0)
        helper.runAfterDelay(2) { speaker.chat("hello across worlds") }
        helper.succeedWhen {
            // The line crosses dimensions and stays on the signed player-chat path.
            val packet = listener.chatPackets().firstOrNull { it.body().content() == "hello across worlds" }
                ?: throw helper.assertionException("the nether listener has not seen ChatAlice's chat line")
            // ...rendered through MCTraveler's chat type: "<green name> <message>".
            val bound = packet.chatType()
            if (bound.chatType().unwrapKey().orElse(null) != MCT_CHAT_TYPE) {
                throw helper.assertionException("chat line was not bound to the mctraveler:chat type")
            }
            if (bound.name() != Component.literal("ChatAlice").withStyle(ChatFormatting.GREEN)) {
                throw helper.assertionException("chat sender name is not the plain green username")
            }
            val decoration = bound.chatType().value().chat()
            if (decoration.translationKey() != "%s %s") {
                throw helper.assertionException("chat decoration is not the Portal's bare '<name> <message>' shape")
            }
            if (decoration.parameters() != listOf(ChatTypeDecoration.Parameter.SENDER, ChatTypeDecoration.Parameter.CONTENT)) {
                throw helper.assertionException("chat decoration parameters are not [sender, content]")
            }
            // The speaker sees their own line the same way.
            if (speaker.chatPackets().none { it.body().content() == "hello across worlds" }) {
                throw helper.assertionException("the speaker did not see their own chat line")
            }
        }
    }

    @GameTest
    fun leaveBroadcastsPortalLineAndSuppressesVanilla(helper: GameTestHelper) {
        val server = helper.level.server
        val observer = TestPlayer.join(server, "LeaveEve")
        val leaver = TestPlayer.join(server, "LeaveFrank")
        val expected = portalLeaveLine("LeaveFrank")
        helper.runAfterDelay(2) { leaver.disconnect() }
        helper.succeedWhen {
            if (observer.systemMessages().any { it.mentionsVanillaPresenceOf("LeaveFrank") }) {
                throw helper.assertionException("vanilla leave message for LeaveFrank was not suppressed")
            }
            if (observer.systemMessages().none { it == expected }) {
                throw helper.assertionException("observer has not seen the Portal leave line for LeaveFrank")
            }
        }
    }

    @GameTest
    fun loginThatDropsBeforePlayAnnouncesNothing(helper: GameTestHelper) {
        val server = helper.level.server
        val observer = TestPlayer.join(server, "GhostGrete")
        // Joins and drops within the same tick: never actually in play, so no lines at all.
        val ghost = TestPlayer.join(server, "GhostHugo")
        ghost.disconnect()
        helper.runAfterDelay(10) {
            val mentionsGhost = observer.systemMessages().any { message ->
                message.string.contains("GhostHugo")
            }
            if (mentionsGhost) {
                throw helper.assertionException("a player who never reached play was announced")
            }
            helper.succeed()
        }
    }

    @GameTest
    fun deathMessageReachesEveryDimensionExactlyOnce(helper: GameTestHelper) {
        val server = helper.level.server
        val observer = TestPlayer.join(server, "DeathGrace")
        val victim = TestPlayer.join(server, "DeathHeidi")
        val nether = server.getLevel(Level.NETHER)!!
        victim.moveTo(nether, 0.0, 100.0, 0.0)
        helper.runAfterDelay(2) {
            victim.player.hurtServer(nether, nether.damageSources().genericKill(), Float.MAX_VALUE)
        }
        helper.runAfterDelay(10) {
            val observerSightings = observer.systemMessages().count { it.isDeathMessageFor("DeathHeidi") }
            if (observerSightings != 1) {
                throw helper.assertionException(
                    "overworld observer saw DeathHeidi's nether death message $observerSightings times, wanted exactly 1",
                )
            }
            val victimSightings = victim.systemMessages().count { it.isDeathMessageFor("DeathHeidi") }
            if (victimSightings != 1) {
                throw helper.assertionException(
                    "the dying player saw their own death message $victimSightings times, wanted exactly 1",
                )
            }
            helper.succeed()
        }
    }

    @GameTest
    fun shrugAndTableflipSendTheEmoticonAsTheChatLine(helper: GameTestHelper) {
        val server = helper.level.server
        val speaker = TestPlayer.join(server, "EmoteIvan")
        val listener = TestPlayer.join(server, "EmoteJudy")
        listener.moveTo(server.getLevel(Level.NETHER)!!, 0.0, 100.0, 0.0)
        helper.runAfterDelay(2) {
            speaker.runCommand("shrug")
            speaker.runCommand("tableflip")
        }
        helper.succeedWhen {
            for (emoticon in listOf("¯\\_(ツ)_/¯", "(╯°□°）╯︵ ┻━┻")) {
                val line = listener.disguisedChatPackets().firstOrNull { it.message() == Component.literal(emoticon) }
                    ?: throw helper.assertionException("nether listener has not seen the $emoticon chat line")
                if (line.chatType().chatType().unwrapKey().orElse(null) != MCT_CHAT_TYPE) {
                    throw helper.assertionException("$emoticon was not sent in the chat display format")
                }
                if (line.chatType().name() != Component.literal("EmoteIvan").withStyle(ChatFormatting.GREEN)) {
                    throw helper.assertionException("$emoticon was not voiced by the green sender name")
                }
                if (speaker.disguisedChatPackets().none { it.message() == Component.literal(emoticon) }) {
                    throw helper.assertionException("the emoting player did not see their own $emoticon line")
                }
            }
        }
    }

    /** `[+] <name> joined`: gray line, dark-gray brackets, green + and name. */
    private fun portalJoinLine(name: String): Component =
        Component.empty().withStyle(ChatFormatting.GRAY)
            .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("+").withStyle(ChatFormatting.GREEN))
            .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(" "))
            .append(Component.literal(name).withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" joined"))

    /** `[-] <name> left.`: gray line, dark-gray brackets, red - and name, trailing period. */
    private fun portalLeaveLine(name: String): Component =
        Component.empty().withStyle(ChatFormatting.GRAY)
            .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("-").withStyle(ChatFormatting.RED))
            .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(" "))
            .append(Component.literal(name).withStyle(ChatFormatting.RED))
            .append(Component.literal(" left."))

    /** True when this is a vanilla `death.*` translatable about [name]. */
    private fun Component.isDeathMessageFor(name: String): Boolean {
        val contents = this.contents as? TranslatableContents ?: return false
        if (!contents.key.startsWith("death.")) return false
        return contents.args.any { arg -> (arg as? Component)?.string == name }
    }

    /** True when this is a vanilla `multiplayer.player.*` translatable naming [name]. */
    private fun Component.mentionsVanillaPresenceOf(name: String): Boolean {
        val contents = this.contents as? TranslatableContents ?: return false
        if (!contents.key.startsWith("multiplayer.player.")) return false
        return contents.args.any { arg -> (arg as? Component)?.string == name || arg == name }
    }

    private companion object {
        /** The chat type the mod ships in its datapack — stated independently of the implementation. */
        val MCT_CHAT_TYPE: ResourceKey<ChatType> =
            ResourceKey.create(Registries.CHAT_TYPE, Identifier.fromNamespaceAndPath("mctraveler", "chat"))
    }
}
