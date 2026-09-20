package eu.mctraveler.gametest

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import eu.mctraveler.rank.Rank
import eu.mctraveler.rank.RankFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundCommandsPacket
import net.minecraft.resources.Identifier
import net.minecraft.stats.Stats

/**
 * `/help` (issue #89): the paged list of what the reader can use, the per-command
 * page, `/help markdown`, and the client command tree that hides audience-gated
 * roots.
 *
 * Seams: the real dispatcher, the messages the player is shown, and the
 * [ClientboundCommandsPacket] the server syncs to a player's client.
 */
class HelpGameTest {

    /** Roots that are wholly admin-only. */
    private val adminRoots = listOf(
        "ban", "unban", "pardon", "banlist", "mute", "unmute", "kick", "warn", "warnings", "unwarn",
        "history", "note", "whois", "seen", "invsee", "inspect", "lookup",
        "rank", "vanish", "embassy", "economy", "set-teleportation-crystal-energy",
    )

    // -- the list --

    @GameTest(maxTicks = 100)
    fun aNonAdminNeverSeesAnAdminCommandOnAnyPage(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "HelpMember")
        val admin = MessageCapturingPlayer.join(helper, "HelpBoss")
        admin.makeAdmin()
        try {
            val member = allListedCommands(helper, player)
            val boss = allListedCommands(helper, admin)
            for (name in adminRoots) {
                helper.assertTrue(name !in member, "a non-admin's /help lists /$name")
                helper.assertTrue(name in boss, "an admin's /help does not list /$name")
            }
            for (name in listOf("help", "region", "msg", "balance", "rtp")) {
                helper.assertTrue(name in member, "a non-admin's /help does not list /$name")
            }
            // Aliases are folded into their primary entry, not listed alongside it.
            for (alias in listOf("rg", "tell", "w", "r")) {
                helper.assertTrue(alias !in member, "the alias /$alias is listed on its own")
            }
            helper.assertValueEqual(member.first(), "help", "the first entry")
            helper.assertValueEqual(
                member.drop(1),
                member.drop(1).sortedBy { it.lowercase() },
                "the entries after /help are alphabetical",
            )
            helper.succeed()
        } finally {
            player.leave()
            admin.leave()
        }
    }

    @GameTest(maxTicks = 100)
    fun theListHasTenEntriesAPageAndClickableNamesAndPages(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "HelpClicker")
        try {
            val first = helpMessage(player, "help")
            val pages = pageCount(first)
            helper.assertTrue(pages >= 2, "expected several pages of commands, got $pages")

            val lines = first.string.split('\n')
            helper.assertValueEqual(lines.first().trim(), "( Help menu )", "the header")
            helper.assertValueEqual(
                lines[1],
                "Type /help <command> to find out what a specific command does.",
                "the hint",
            )
            helper.assertValueEqual(lines.size, 10 + 3, "a full page is header + hint + 10 commands + footer")
            helper.assertTrue(lines.last().contains("( Page 1 2"), "the footer: '${lines.last()}'")
            helper.assertTrue(lines[2].startsWith("/help"), "/help is first on page 1: '${lines[2]}'")

            // Every command name is a button running /help <name>; page numbers run /help <n>.
            val clicks = clickable(first)
            helper.assertValueEqual((clicks["/help"] ?: "none"), "/help help", "the /help button")
            helper.assertValueEqual((clicks["/away"] ?: "none"), "/help away", "the /away button")
            helper.assertValueEqual((clicks["2"] ?: "none"), "/help 2", "the page 2 button")
            helper.assertTrue("1" !in clicks, "the current page must not be a button")

            val second = helpMessage(player, "help 2")
            helper.assertTrue(second.string.split('\n').last().contains("( Page 1 2"), "page 2 footer")
            helper.assertValueEqual((clickable(second)["1"] ?: "none"), "/help 1", "the page 1 button on page 2")
            helper.assertTrue("2" !in clickable(second), "the current page 2 must not be a button")
            helper.assertTrue(
                second.string.split('\n')[2] != lines[2],
                "page 2 starts with a different command than page 1",
            )
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest(maxTicks = 100)
    fun outOfRangePagesAndUnknownCommandsAreErrors(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "HelpErrors")
        try {
            val pages = pageCount(helpMessage(player, "help"))
            for (page in listOf("0", "-1", (pages + 1).toString())) {
                val reply = helpMessage(player, "help $page").string
                helper.assertTrue(
                    reply.startsWith("ERROR ") && reply.contains("between 1 and $pages"),
                    "/help $page answered '$reply'",
                )
            }
            val unknown = helpMessage(player, "help nonsense").string
            helper.assertTrue(unknown.startsWith("ERROR Unknown command /nonsense"), "answered '$unknown'")
            // A command this player cannot use is indistinguishable from one that does not exist.
            val hidden = helpMessage(player, "help ban").string
            helper.assertValueEqual(hidden, unknown.replace("nonsense", "ban"), "/help ban as a non-admin")
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    // -- one command --

    @GameTest(maxTicks = 100)
    fun helpRegionShowsItsAliasOptionsAndExplanation(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "HelpRegion")
        val admin = MessageCapturingPlayer.join(helper, "HelpRegionBoss")
        admin.makeAdmin()
        try {
            val page = helpMessage(player, "help region")
            val lines = page.string.split('\n')
            helper.assertValueEqual(lines[0].trim(), "( Help menu )", "the header")
            helper.assertValueEqual(lines[1], "/region, /rg", "the names line")
            helper.assertValueEqual(
                lines[2],
                "Available options are: rename, add, remove, delete, start, end, extend, size, shrink, flags",
                "a non-admin's options (no bounds/locate)",
            )
            helper.assertTrue(lines[3].startsWith("A region protects a piece of land"), "the explanation: '${lines[3]}'")
            helper.assertTrue(
                lines.any { it.contains("Call for an admin to expand it for you.") },
                "the admin-expansion note is missing",
            )
            for (form in listOf("help /region", "help rg", "help /rg", "help REGION")) {
                helper.assertValueEqual(helpMessage(player, form).string, page.string, "/$form")
            }
            helper.assertValueEqual(
                runsOf(page).first { it.text == "/region, /rg" }.color.orEmpty(),
                "white",
                "the names line colour",
            )
            helper.assertValueEqual(
                runsOf(page).first { it.text.startsWith("Available options") }.color.orEmpty(),
                "gray",
                "the options line colour",
            )

            val adminLines = helpMessage(admin, "help region").string.split('\n')
            helper.assertTrue(
                adminLines[2].endsWith("shrink, flags, bounds, locate"),
                "an admin's options include bounds and locate: '${adminLines[2]}'",
            )
            helper.succeed()
        } finally {
            player.leave()
            admin.leave()
        }
    }

    @GameTest(maxTicks = 100)
    fun everyCommandTheModRegistersHasADescription(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "HelpDescriber")
        admin.makeAdmin()
        try {
            val mod = adminRoots + listOf(
                "away", "chat", "shrug", "tableflip", "msg", "reply", "name", "balance", "pay", "store",
                "map", "notepad", "passport", "postcard", "rtp", "dragonfight", "norain", "switch", "region",
                "spawn1", "spawn2",
            )
            for (name in mod) {
                val reply = helpMessage(admin, "help $name").string
                helper.assertTrue(!reply.startsWith("ERROR"), "/help $name failed: $reply")
                helper.assertTrue(
                    !reply.contains("No description is available"),
                    "/help $name has no description: $reply",
                )
            }
            helper.succeed()
        } finally {
            admin.leave()
        }
    }

    // -- markdown --

    @GameTest(maxTicks = 100)
    fun helpMarkdownRendersEveryCodeInItsOwnStyle(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "HelpMarkdown")
        try {
            val page = helpMessage(player, "help markdown")
            val runs = runsOf(page)
            helper.assertValueEqual(page.string.split('\n')[0].trim(), "( Help menu )", "the header")
            for (code in "0123456789abcdef") {
                val format = ChatFormatting.getByCode(code)!!
                val run = runs.firstOrNull { it.text == "%$code ${format.name.lowercase()}" }
                    ?: throw helper.assertionException("%$code is not on the markdown page")
                helper.assertValueEqual(run.color.orEmpty(), format.name.lowercase(), "the colour of %$code")
            }
            for (code in "klmnor") {
                helper.assertTrue(
                    runs.any { it.text.startsWith("%$code ") },
                    "%$code is not on the markdown page",
                )
            }
            helper.assertTrue(page.string.contains("Donators and admins"), "the Donator note is missing")
            helper.assertTrue(page.string.contains("<name>"), "the <name> note is missing")
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    // -- the client command tree --

    @GameTest(maxTicks = 100)
    fun aNonAdminsClientTreeLacksAdminRootsAndAnAdminsHasThem(helper: GameTestHelper) {
        val member = MessageCapturingPlayer.join(helper, "HelpTreeMember")
        val admin = MessageCapturingPlayer.join(helper, "HelpTreeBoss")
        admin.makeAdmin()
        try {
            val memberTree = clientRoots(helper, member)
            val adminTree = clientRoots(helper, admin)
            for (name in adminRoots) {
                helper.assertTrue(name !in memberTree, "a non-admin's client tree offers /$name")
                helper.assertTrue(name in adminTree, "an admin's client tree lacks /$name")
            }
            for (name in listOf("help", "region", "rg", "msg", "balance", "rtp", "away")) {
                helper.assertTrue(name in memberTree, "a non-admin's client tree lacks /$name")
            }
            // Vanilla's /help was replaced, not duplicated.
            helper.assertTrue(memberTree.count { it == "help" } == 1, "expected exactly one /help")
            helper.succeed()
        } finally {
            member.leave()
            admin.leave()
        }
    }

    @GameTest(maxTicks = 100)
    fun changingARankResendsTheCommandTree(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "HelpRankBoss")
        admin.makeAdmin()
        val target = MessageCapturingPlayer.join(helper, "HelpRankTarget")
        try {
            PacketCapture.drain(target)
            RankFeature.setRank(target, Rank.DONATOR)
            helper.assertTrue(
                PacketCapture.drainOf<ClientboundCommandsPacket>(target).isNotEmpty(),
                "setRank did not re-send the command tree",
            )
            PacketCapture.drain(target)
            admin.runCommand("rank set HelpRankTarget traveler")
            helper.assertTrue(
                PacketCapture.drainOf<ClientboundCommandsPacket>(target).isNotEmpty(),
                "/rank set did not re-send the target's command tree",
            )
            helper.succeed()
        } finally {
            admin.leave()
            target.leave()
        }
    }

    @GameTest(maxTicks = 260)
    fun promotionToTravelerResendsTheCommandTree(helper: GameTestHelper) {
        val player = TestPlayer.join(helper.level.server, "HelpPromoted")
        val before = player.clientboundPackets().count { it is ClientboundCommandsPacket }
        val playTime = Stats.CUSTOM.get(Stats.PLAY_TIME)
        player.player.stats.setValue(player.player, playTime, RankFeature.PROMOTION_TICKS.toInt())

        helper.succeedWhen {
            helper.assertTrue(RankFeature.rankOf(player.player) == Rank.TRAVELER, "the Newbie was never promoted")
            helper.assertTrue(
                player.clientboundPackets().count { it is ClientboundCommandsPacket } > before,
                "the promotion did not re-send the command tree",
            )
            player.disconnect()
        }
    }

    // -- helpers --

    /** Runs `/<command>` as [player] and returns the one message it produced. */
    private fun helpMessage(player: MessageCapturingPlayer, command: String): Component {
        player.messages.clear()
        player.runCommand(command)
        return player.messages.last()
    }

    /** The number of pages, read off the footer's numbers. */
    private fun pageCount(firstPage: Component): Int =
        firstPage.string.split('\n').last().substringAfter("Page").substringBefore(")").trim().split(' ').size

    /** Every command name listed on every page of [player]'s `/help`, in order. */
    private fun allListedCommands(helper: GameTestHelper, player: MessageCapturingPlayer): List<String> {
        val first = helpMessage(player, "help")
        val pages = pageCount(first)
        val names = mutableListOf<String>()
        for (page in 1..pages) {
            val message = if (page == 1) first else helpMessage(player, "help $page")
            val lines = message.string.split('\n')
            helper.assertValueEqual(lines.first().trim(), "( Help menu )", "the header of page $page")
            lines.drop(2).dropLast(1).mapTo(names) { it.substringBefore(' ').removePrefix("/") }
        }
        return names
    }

    /** Text of every run carrying a `/help ...` click, by the text it covers. */
    private fun clickable(component: Component): Map<String, String> =
        component.toFlatList(component.style).mapNotNull {
            val click = it.style.clickEvent as? ClickEvent.RunCommand ?: return@mapNotNull null
            it.string to click.command()
        }.toMap()

    /** The root command names in the tree the server just synced to [player]'s client. */
    private fun clientRoots(helper: GameTestHelper, player: MessageCapturingPlayer): List<String> {
        PacketCapture.drain(player)
        helper.level.server.commands.sendCommands(player)
        val packet = PacketCapture.drainOf<ClientboundCommandsPacket>(player).lastOrNull()
            ?: throw helper.assertionException("no command tree was sent to ${player.gameProfile.name}")
        val context = Commands.createValidationContext(helper.level.server.registryAccess())
        val root = packet.getRoot(context, object : ClientboundCommandsPacket.NodeBuilder<Any> {
            override fun createLiteral(id: String): ArgumentBuilder<Any, *> = LiteralArgumentBuilder.literal<Any>(id)

            @Suppress("UNCHECKED_CAST")
            override fun createArgument(
                id: String,
                argumentType: ArgumentType<*>,
                suggestionId: Identifier?,
            ): ArgumentBuilder<Any, *> =
                RequiredArgumentBuilder.argument<Any, Any>(id, argumentType as ArgumentType<Any>)

            override fun configure(
                input: ArgumentBuilder<Any, *>,
                executable: Boolean,
                restricted: Boolean,
            ): ArgumentBuilder<Any, *> = input
        })
        return root.children.map { it.name }
    }
}
