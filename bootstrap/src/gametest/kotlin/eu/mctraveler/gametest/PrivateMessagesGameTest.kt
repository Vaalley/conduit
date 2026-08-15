package eu.mctraveler.gametest

import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/**
 * Private messaging parity (spec stories 12-14; inventory §2.4): `/msg`, `/reply` (`/r`),
 * and the vanilla aliases `/tell` and `/w`. Cases are mined from the Portal's
 * ChatFeature tests and the inventory's exact formats and errors.
 */
class PrivateMessagesGameTest {

    /** The chat lines received since the last drain, as rendered runs. */
    private fun FakePlayer.receivedRuns(): List<List<Run>> = receivedChatLines().map(::runsOf)

    /** The Portal's private-message line: `<green sender> <gray →> <green target>: <message>`. */
    private fun privateLine(sender: String, target: String, message: String): List<Run> = listOf(
        Run(sender, color = "green"),
        Run(" "),
        Run("→", color = "gray"),
        Run(" "),
        Run(target, color = "green"),
        Run(": "),
        Run(message),
    )

    /** The Portal's ERROR line: red+bold prefix, gray content. */
    private fun errorLine(content: String): List<Run> = listOf(
        Run("ERROR", color = "red", bold = true),
        Run(" "),
        Run(content, color = "gray"),
    )

    @GameTest
    fun msgToSelfIsRefusedWithTheExactError(helper: GameTestHelper) {
        helper.withFakePlayers("SelfSam") { (sam) ->
            sam.runCommand("/msg SelfSam test")

            helper.assertValueEqual(
                sam.receivedRuns(),
                listOf(errorLine("You can't send a message to yourself")),
                "lines SelfSam received",
            )
        }
        helper.succeed()
    }

    @GameTest
    fun msgToAnUnknownPlayerGetsThePortalNotFoundError(helper: GameTestHelper) {
        helper.withFakePlayers("LoneLarry") { (larry) ->
            larry.runCommand("/msg Ghost hi")

            val expected = listOf(
                Run("Player ", color = "gray"),
                Run("Ghost", color = "red"),
                Run(" not found or is offline", color = "gray"),
            )
            helper.assertValueEqual(
                larry.receivedRuns(),
                listOf(expected),
                "lines LoneLarry received",
            )
        }
        helper.succeed()
    }

    @GameTest
    fun msgDeliversTheIdenticalLineToBothParties(helper: GameTestHelper) {
        helper.withFakePlayers("MsgAlice", "MsgBob") { (alice, bob) ->
            alice.runCommand("/msg MsgBob Hello there!")

            val expected = listOf(privateLine("MsgAlice", "MsgBob", "Hello there!"))
            helper.assertValueEqual(bob.receivedRuns(), expected, "lines MsgBob received")
            helper.assertValueEqual(alice.receivedRuns(), expected, "lines MsgAlice received")
        }
        helper.succeed()
    }

    @GameTest
    fun msgResolvesTheTargetNameCaseInsensitively(helper: GameTestHelper) {
        // The Portal's onlinePlayer parser matched usernames case-insensitively; the line
        // always shows the target's canonical name.
        helper.withFakePlayers("CaseCleo", "CaseDrew") { (cleo, drew) ->
            cleo.runCommand("/msg casedrew hey")

            helper.assertValueEqual(
                drew.receivedRuns(),
                listOf(privateLine("CaseCleo", "CaseDrew", "hey")),
                "lines CaseDrew received",
            )
        }
        helper.succeed()
    }

    @GameTest
    fun replyAnswersTheLastPersonWhoMessagedYou(helper: GameTestHelper) {
        helper.withFakePlayers("ReplyAnna", "ReplyBen") { (anna, ben) ->
            // /msg stores reply partners in both directions: Ben never ran /msg himself.
            anna.runCommand("/msg ReplyBen Hi")
            ben.runCommand("/reply Hey back!")

            val expected = listOf(privateLine("ReplyBen", "ReplyAnna", "Hey back!"))
            helper.assertValueEqual(
                anna.receivedRuns().drop(1),
                expected,
                "lines ReplyAnna received after her own /msg",
            )
            helper.assertValueEqual(
                ben.receivedRuns().drop(1),
                expected,
                "lines ReplyBen received after Anna's /msg",
            )
        }
        helper.succeed()
    }

    @GameTest
    fun rIsAnAliasOfReply(helper: GameTestHelper) {
        helper.withFakePlayers("ShortAda", "ShortBo") { (ada, bo) ->
            ada.runCommand("/msg ShortBo test")
            bo.runCommand("/r reply test")

            helper.assertValueEqual(
                ada.receivedRuns().drop(1),
                listOf(privateLine("ShortBo", "ShortAda", "reply test")),
                "lines ShortAda received after her own /msg",
            )
        }
        helper.succeed()
    }

    @GameTest
    fun replyWithNoOneToReplyToErrors(helper: GameTestHelper) {
        helper.withFakePlayers("LonelyLiv") { (liv) ->
            liv.runCommand("/reply test")

            helper.assertValueEqual(
                liv.receivedRuns(),
                listOf(errorLine("You have no-one to reply to")),
                "lines LonelyLiv received",
            )
        }
        helper.succeed()
    }

    @GameTest
    fun replyWhenYourPartnerWentOfflineErrors(helper: GameTestHelper) {
        helper.withFakePlayers("GoneDana", "GoneEli") { (dana, eli) ->
            dana.runCommand("/msg GoneEli hi")
            eli.disconnect()
            dana.clearReceived()
            dana.runCommand("/reply still there?")

            helper.assertValueEqual(
                dana.receivedRuns(),
                listOf(errorLine("The player you were messaging is no longer online")),
                "lines GoneDana received",
            )
        }
        helper.succeed()
    }

    @GameTest
    fun replyAfterRejoiningHasNoPartner(helper: GameTestHelper) {
        // The Portal kept reply partners per connection (a WeakMap on the session object):
        // your own entry does not survive your disconnect.
        val server = helper.level.server
        val fayId = UUID.randomUUID()
        val gus = FakePlayer.join(server, "RejoinGus")
        var fay = FakePlayer.join(server, "RejoinFay", fayId)
        try {
            fay.runCommand("/msg RejoinGus hi")
            fay.disconnect()
            fay = FakePlayer.join(server, "RejoinFay", fayId)
            fay.clearReceived()
            fay.runCommand("/reply back again")

            helper.assertValueEqual(
                fay.receivedRuns(),
                listOf(errorLine("You have no-one to reply to")),
                "lines rejoined RejoinFay received",
            )
        } finally {
            fay.disconnect()
            gus.disconnect()
        }
        helper.succeed()
    }

    @GameTest
    fun replyReachesAPartnerWhoLeftAndReturned(helper: GameTestHelper) {
        // Intent Parity deviation from the Portal: its stale session reference kept erroring
        // after the partner returned; the plain intent of "no longer online" is to check now.
        val server = helper.level.server
        val ivyId = UUID.randomUUID()
        val hal = FakePlayer.join(server, "ReturnHal")
        var ivy = FakePlayer.join(server, "ReturnIvy", ivyId)
        try {
            hal.runCommand("/msg ReturnIvy hi")
            ivy.disconnect()
            ivy = FakePlayer.join(server, "ReturnIvy", ivyId)
            ivy.clearReceived()
            hal.runCommand("/reply welcome back")

            helper.assertValueEqual(
                ivy.receivedRuns(),
                listOf(privateLine("ReturnHal", "ReturnIvy", "welcome back")),
                "lines returned ReturnIvy received",
            )
        } finally {
            ivy.disconnect()
            hal.disconnect()
        }
        helper.succeed()
    }

    @GameTest
    fun tellAndWAliasMsgIncludingTheReplyMap(helper: GameTestHelper) {
        helper.withFakePlayers("AliasTom", "AliasWil") { (tom, wil) ->
            tom.runCommand("/tell AliasWil via tell")
            wil.runCommand("/reply got it")
            tom.runCommand("/w AliasWil via w")

            val expected = listOf(
                privateLine("AliasTom", "AliasWil", "via tell"),
                privateLine("AliasWil", "AliasTom", "got it"),
                privateLine("AliasTom", "AliasWil", "via w"),
            )
            helper.assertValueEqual(wil.receivedRuns(), expected, "lines AliasWil received")
            helper.assertValueEqual(tom.receivedRuns(), expected, "lines AliasTom received")
        }
        helper.succeed()
    }

    @GameTest
    fun msgAndItsAliasesTabCompleteOnlinePlayerNames(helper: GameTestHelper) {
        helper.withFakePlayers("SuggSam", "SuggSue") { (sam, _) ->
            val dispatcher = helper.level.server.commands.dispatcher
            val source = sam.player.createCommandSourceStack()
            fun completions(input: String): List<String> =
                dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).join().list.map { it.text }

            helper.assertValueEqual(completions("msg Sugg"), listOf("SuggSam", "SuggSue"), "completions for 'msg Sugg'")
            helper.assertValueEqual(completions("tell Sugg"), listOf("SuggSam", "SuggSue"), "completions for 'tell Sugg'")
            helper.assertValueEqual(completions("w SuggSu"), listOf("SuggSue"), "completions for 'w SuggSu'")
        }
        helper.succeed()
    }

    @GameTest
    fun replyDoesNotItselfUpdateTheReplyMap(helper: GameTestHelper) {
        // Portal quirk, preserved: only /msg updates reply partners. Bob's partner is Carol
        // (she messaged him last); Alice replying to Bob must not steal that back.
        helper.withFakePlayers("QuirkAlice", "QuirkBob", "QuirkCarol") { (alice, bob, carol) ->
            alice.runCommand("/msg QuirkBob one")
            carol.runCommand("/msg QuirkBob two")
            alice.runCommand("/reply three")
            bob.runCommand("/reply four")

            helper.assertValueEqual(
                carol.receivedRuns(),
                listOf(
                    privateLine("QuirkCarol", "QuirkBob", "two"),
                    privateLine("QuirkBob", "QuirkCarol", "four"),
                ),
                "lines QuirkCarol received",
            )
            helper.assertValueEqual(
                alice.receivedRuns(),
                listOf(
                    privateLine("QuirkAlice", "QuirkBob", "one"),
                    privateLine("QuirkAlice", "QuirkBob", "three"),
                ),
                "lines QuirkAlice received",
            )
        }
        helper.succeed()
    }
}
