package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.moderation.ModerationFeature
import java.net.InetSocketAddress
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.players.NameAndId

class ModerationGameTest {

    @GameTest(maxTicks = 120)
    fun offlineBanRefusesLoginAndUnbanRestoresAccess(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "ModBanAdmin").also { op(server, it) }
        val target = TestPlayer.join(server, "ModOfflineTarget")
        target.disconnect()

        helper.runAfterDelay(2) {
            admin.runCommand("ban ModOfflineTarget griefing")
            val punishment = checkNotNull(MCTraveler.persistence?.punishments?.activeBan(target.player.uuid))
            val denied = server.playerList.canPlayerLogin(
                InetSocketAddress("127.0.0.1", 25565),
                NameAndId(target.player.uuid, target.name),
            )
            helper.assertTrue(denied != null && denied.string.contains("banned"), "ban denial was $denied")
            helper.assertTrue(denied?.string?.contains("griefing") == true, "ban reason was $denied")
            admin.runCommand("banlist")
            helper.assertTrue(admin.systemMessages().any { it.string.contains("#${punishment.id} ModOfflineTarget — griefing") }, "banlist was ${admin.systemMessages()}")
            admin.runCommand("unban ModOfflineTarget")
            helper.assertTrue(
                ModerationFeature.loginDenial(NameAndId(target.player.uuid, target.name)) == null,
                "unban still denies login",
            )
            val loginResult = server.playerList.canPlayerLogin(
                InetSocketAddress("127.0.0.1", 25565),
                NameAndId(target.player.uuid, target.name),
            )
            helper.assertTrue(
                loginResult == null || loginResult.toString().contains("server_full"),
                "login denial after unban",
            )
            cleanup(admin)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 120)
    fun onlineBanDisconnectsAndKickIsRecorded(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "ModOnlineAdmin").also { op(server, it) }
        val banned = TestPlayer.join(server, "ModOnlineTarget")
        val kicked = TestPlayer.join(server, "ModKickTarget")

        helper.runAfterDelay(2) {
            admin.runCommand("ban ModOnlineTarget reason")
            admin.runCommand("kick ModKickTarget testing")
            admin.runCommand("history ModKickTarget")
        }
        helper.succeedWhen {
            helper.assertTrue(server.playerList.getPlayerByName("ModOnlineTarget") == null, "online ban did not disconnect the target")
            helper.assertTrue(server.playerList.getPlayerByName("ModKickTarget") == null, "kick did not disconnect the target")
            helper.assertTrue(admin.systemMessages().any { it.string.contains("KICK") && it.string.contains("testing") }, "kick history was ${admin.systemMessages()}")
            cleanup(admin)
        }
    }

    @GameTest(maxTicks = 120)
    fun expiredBanAllowsLoginAndHistoryShowsExpired(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "ModExpiryAdmin").also { op(server, it) }
        val uuid = UUID.randomUUID()
        val name = "ModExpiredTarget"
        MCTraveler.persistence?.names?.record(uuid, name)
        MCTraveler.persistence?.punishments?.add(
            eu.mctraveler.moderation.PunishmentType.BAN,
            uuid,
            name,
            admin.player.uuid,
            admin.name,
            "old ban",
            System.currentTimeMillis() - 1,
        )

        helper.runAfterDelay(2) {
            helper.assertTrue(
                ModerationFeature.loginDenial(NameAndId(uuid, name)) == null,
                "expired punishment still denies login",
            )
            val loginResult = server.playerList.canPlayerLogin(
                InetSocketAddress("127.0.0.1", 25565),
                NameAndId(uuid, name),
            )
            helper.assertTrue(
                loginResult == null || loginResult.toString().contains("server_full"),
                "expired ban login denial",
            )
            admin.runCommand("history $name")
            helper.assertTrue(admin.systemMessages().any { it.string.contains("[expired]") }, "history was ${admin.systemMessages()}")
            cleanup(admin)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 140)
    fun muteBlocksChatAndPrivateMessagesThenUnmuteRestoresChat(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "ModMuteAdmin").also { op(server, it) }
        val muted = TestPlayer.join(server, "ModMuted")
        val recipient = TestPlayer.join(server, "ModRecipient")

        helper.runAfterDelay(2) {
            admin.runCommand("mute ModMuted 1h spam")
            val beforeChat = recipient.chatPackets().size
            muted.chat("blocked")
            muted.runCommand("msg ModRecipient hidden")
            helper.assertValueEqual(recipient.chatPackets().size, beforeChat, "muted private message reached recipient")
            helper.assertTrue(muted.systemMessages().any { it.string.contains("You are muted") }, "mute message was ${muted.systemMessages()}")
            admin.runCommand("unmute ModMuted")
            muted.chat("allowed")
            helper.runAfterDelay(2) {
                helper.assertTrue(recipient.chatPackets().any { it.sender() == muted.player.uuid }, "unmuted chat did not reach recipient")
                cleanup(admin, muted, recipient)
                helper.succeed()
            }
        }
    }

    @GameTest(maxTicks = 120)
    fun warningsUnwarnAndNotesAppearInHistory(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "ModWarnAdmin").also { op(server, it) }
        val target = TestPlayer.join(server, "ModWarnTarget")

        helper.runAfterDelay(2) {
            admin.runCommand("warn ModWarnTarget rule")
            helper.assertTrue(target.systemMessages().any { it.string.contains("You have been warned") }, "warning was ${target.systemMessages()}")
            val warning = checkNotNull(MCTraveler.persistence?.punishments?.history(target.player.uuid)?.first { it.type.name == "WARN" })
            admin.runCommand("warnings ModWarnTarget")
            helper.assertTrue(admin.systemMessages().any { it.string.contains("#${warning.id}") }, "warnings were ${admin.systemMessages()}")
            admin.runCommand("unwarn ${warning.id}")
            admin.runCommand("warnings ModWarnTarget")
            helper.assertTrue(admin.systemMessages().any { it.string.contains("No active warnings for ModWarnTarget") }, "empty warnings were ${admin.systemMessages()}")
            admin.runCommand("note ModWarnTarget follow-up")
            admin.runCommand("history ModWarnTarget")
            helper.assertTrue(admin.systemMessages().any { it.string.contains("NOTE") && it.string.contains("follow-up") }, "note history was ${admin.systemMessages()}")
            cleanup(admin, target)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 120)
    fun seenReportsOfflineBlockPositionAndUnknownPlayers(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "ModSeenAdmin").also { op(server, it) }
        val target = TestPlayer.join(server, "ModSeenTarget")
        target.player.setPos(4.8, 12.0, 6.2)
        target.disconnect()

        helper.runAfterDelay(2) {
            admin.runCommand("seen ModSeenTarget")
            helper.assertTrue(admin.systemMessages().any { it.string.contains("ModSeenTarget") && it.string.contains("world") && it.string.contains("4 12 6") }, "seen output was ${admin.systemMessages()}")
            admin.runCommand("seen NeverJoinedModerationPlayer")
            helper.assertTrue(admin.systemMessages().any { it.string.contains("Unknown player") }, "unknown seen output was ${admin.systemMessages()}")
            cleanup(admin)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun whoisReportsUuidRankAndOnlineStatus(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "ModWhoisAdmin").also { op(server, it) }

        helper.runAfterDelay(2) {
            admin.runCommand("whois ModWhoisAdmin")
            val lines = admin.systemMessages().map { it.string }
            helper.assertTrue(lines.any { it == "UUID: ${admin.player.uuid}" }, "whois uuid lines were $lines")
            helper.assertTrue(lines.any { it.startsWith("Rank: ") }, "whois rank lines were $lines")
            helper.assertTrue(lines.any { it == "Online: true" }, "whois online lines were $lines")
            cleanup(admin)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun moderationCommandsAreUnavailableToNonOperators(helper: GameTestHelper) {
        val server = helper.level.server
        val player = TestPlayer.join(server, "ModNonOp")
        val before = MCTraveler.persistence?.punishments?.history(player.player.uuid).orEmpty().size
        val commands = listOf(
            "ban", "unban", "banlist", "mute", "unmute", "kick", "warn", "warnings",
            "unwarn", "history", "note", "whois", "seen", "inspect", "lookup", "invsee", "pardon",
        )

        helper.runAfterDelay(2) {
            commands.forEach { name ->
                val node = server.commands.dispatcher.root.getChild(name)
                    ?: throw helper.assertionException("/$name is not registered")
                helper.assertFalse(node.requirement.test(player.player.createCommandSourceStack()), "/$name is visible to non-op")
            }
            player.runCommand("warn ModNonOp should-not-run")
            helper.assertValueEqual(
                MCTraveler.persistence?.punishments?.history(player.player.uuid).orEmpty().size,
                before,
                "non-op moderation state changes",
            )
            cleanup(player)
            helper.succeed()
        }
    }

    private fun op(server: net.minecraft.server.MinecraftServer, player: TestPlayer) {
        server.playerList.op(player.player.nameAndId())
    }

    private fun cleanup(vararg players: TestPlayer) {
        players.forEach(TestPlayer::disconnect)
    }
}
