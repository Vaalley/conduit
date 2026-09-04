package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.rank.Rank
import eu.mctraveler.rank.RankFeature
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.stats.Stats
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SignBlockEntity

/**
 * The rank ladder: a brand-new account is welcomed and starts a Newbie
 * (cannot create regions); an hour of play time promotes them to Traveler
 * (the mod's long-standing default); `/rank set` (admin-only) grants Donator,
 * whose perks are a bigger region cap and sign markdown.
 */
class RankGameTest {

    // ---- first join ----

    @GameTest(maxTicks = 100)
    fun aBrandNewAccountIsWelcomedAndBecomesANewbie(helper: GameTestHelper) {
        val server = helper.level.server
        val observer = TestPlayer.join(server, "RankObserver")
        RankFeature.setRank(observer.player, Rank.TRAVELER)
        // A genuinely fresh login, through the real path — the one thing that
        // must NOT already have a rank recorded.
        val newcomer = TestPlayer.join(server, "RankNewcomer")

        helper.succeedWhen {
            helper.assertTrue(
                RankFeature.rankOf(newcomer.player) == Rank.NEWBIE,
                "a brand-new account did not become a Newbie",
            )
            val expected = welcomeLine("RankNewcomer")
            if (observer.systemMessages().none { it == expected }) {
                throw helper.assertionException(
                    "the welcome broadcast for a new player never arrived: " +
                        observer.systemMessages().map { it.string },
                )
            }
            observer.disconnect()
            newcomer.disconnect()
        }
    }

    @GameTest(maxTicks = 100)
    fun anExistingRecordWithNoRankIsGrandfatheredInAsATraveler(helper: GameTestHelper) {
        val server = helper.level.server
        val player = TestPlayer.join(server, "RankExisting")
        // Give the record something else to exist for, without ever touching
        // rank — the shape of every account from before this feature shipped.
        checkNotNull(MCTraveler.persistence).players.setLastWorld(player.player.uuid, "primary")

        check(RankFeature.rankOf(player.player) == Rank.TRAVELER) {
            "an existing, rank-less player was not grandfathered in as a Traveler"
        }
        player.disconnect()
        helper.succeed()
    }

    // ---- region gating ----

    @GameTest(maxTicks = 40)
    fun newbiesCannotStartARegion(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "RankNewbieRg")
        RankFeature.setRank(player, Rank.NEWBIE)
        player.standAt(helper, 1.0, 1.0, 1.0)
        player.runCommand("rg start")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("Newbies cannot create regions yet. Keep playing to become a Traveler!"),
            "the Newbie /rg start refusal",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun aDonatorsRegionCapIsTenThousandBlocks(helper: GameTestHelper) {
        val traveler = MessageCapturingPlayer.join(helper, "RankTravCap")
        // Far from every test structure, like the admin-size region test.
        traveler.standAt(helper, 40000.0, 1.0, 0.0)
        traveler.runCommand("rg start")
        traveler.standAt(helper, 40080.0, 1.0, 80.0) // 81×81 = 6561 blocks: over Traveler, under Donator
        traveler.runCommand("rg end")
        helper.assertValueEqual(
            traveler.messages.last(),
            Paint.error("Region too large (6561 blocks). Limit is 5000 blocks. Ask an admin to create it."),
            "a Traveler is capped at 5000",
        )

        val donator = MessageCapturingPlayer.join(helper, "RankDonCap")
        RankFeature.setRank(donator, Rank.DONATOR)
        donator.standAt(helper, 40200.0, 1.0, 0.0)
        donator.runCommand("rg start")
        donator.standAt(helper, 40280.0, 1.0, 80.0) // same 6561: fits the Donator cap
        donator.runCommand("rg end")
        helper.assertTrue(
            donator.messages.last().string.contains("created!"),
            "a Donator's 6561-block region was refused: ${donator.messages.last().string}",
        )

        traveler.leave()
        donator.leave()
        helper.succeed()
    }

    // ---- promotion ----

    @GameTest(maxTicks = 260)
    fun anHourOfPlayTimePromotesANewbieToATraveler(helper: GameTestHelper) {
        val server = helper.level.server
        val player = TestPlayer.join(server, "RankGrind")
        val playTimeStat = Stats.CUSTOM.get(Stats.PLAY_TIME)
        player.player.stats.setValue(player.player, playTimeStat, RankFeature.PROMOTION_TICKS.toInt())

        helper.succeedWhen {
            helper.assertTrue(
                RankFeature.rankOf(player.player) == Rank.TRAVELER,
                "the Newbie was never promoted",
            )
            val promo = player.systemMessages().firstOrNull { it.string.startsWith("You can now create") }
                ?: throw helper.assertionException("the promotion message never arrived")
            val runs = runsOf(promo)
            val expectedRuns = listOf(
                Run("You can now create ", "gray"),
                Run("regions", "green"),
                Run(" to protect your stuff!", "gray"),
                Run("\n", "gray"),
                Run("Get started using ", "gray"),
                Run("/region start", "green"),
            )
            helper.assertValueEqual(runs, expectedRuns, "the promotion message's runs")
            val click = promo.siblings.lastOrNull()?.style?.clickEvent
            if (click !is ClickEvent.RunCommand || click.command() != "/region start") {
                throw helper.assertionException("/region start is not click-to-run: $click")
            }
            player.disconnect()
        }
    }

    // ---- /rank set ----

    @GameTest(maxTicks = 40)
    fun rankSetIsAdminOnly(helper: GameTestHelper) {
        val stranger = MessageCapturingPlayer.join(helper, "RankSetStranger")
        val target = MessageCapturingPlayer.join(helper, "RankSetTarget")
        stranger.runCommand("rank set RankSetTarget donator")
        helper.assertValueEqual(
            stranger.messages.last(),
            Paint.error("You must be an admin to use this command"),
            "a non-admin /rank set reply",
        )
        helper.assertTrue(RankFeature.rankOf(target) != Rank.DONATOR, "a non-admin changed someone's rank")
        stranger.leave()
        target.leave()
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun anAdminCanSetAnOnlinePlayersRank(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "RankSetAdmin")
        admin.makeAdmin()
        val target = MessageCapturingPlayer.join(helper, "RankSetOnline")
        admin.runCommand("rank set RankSetOnline donator")
        helper.assertTrue(RankFeature.rankOf(target) == Rank.DONATOR, "the online target's rank did not change")
        admin.leave()
        target.leave()
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun anAdminCanSetAnOfflinePlayersRankByName(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "RankSetAdmin2")
        admin.makeAdmin()
        val target = MessageCapturingPlayer.join(helper, "RankSetOffline")
        val targetUuid = target.uuid
        target.leave()

        admin.runCommand("rank set RankSetOffline donator")

        val stored = checkNotNull(MCTraveler.persistence).players.rank(targetUuid)
        helper.assertValueEqual(checkNotNull(stored), Rank.DONATOR.name, "the offline target's stored rank")
        admin.leave()
        helper.succeed()
    }

    // ---- Donator perks ----

    @GameTest(maxTicks = 40)
    fun aDonatorsSignMarkdownIsColored(helper: GameTestHelper) {
        val donator = MessageCapturingPlayer.join(helper, "RankSignDon")
        RankFeature.setRank(donator, Rank.DONATOR)
        val sign = helper.placeRankTestSign(donator)
        donator.writesOnRankTestSign(helper, "%aGreen%r plain")

        helper.assertValueEqual(
            runsOf(sign.frontText.getMessage(0, false)),
            listOf(Run("Green", "green"), Run(" plain")),
            "the Donator's markdown was not applied to the sign",
        )
        donator.leave()
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun aNonDonatorsPercentSignsAreLiteralOnASign(helper: GameTestHelper) {
        val traveler = MessageCapturingPlayer.join(helper, "RankSignTrav")
        val sign = helper.placeRankTestSign(traveler)
        traveler.writesOnRankTestSign(helper, "%aGreen")

        helper.assertValueEqual(
            sign.frontText.getMessage(0, false).string,
            "%aGreen",
            "a non-Donator's % codes were not left literal",
        )
        traveler.leave()
        helper.succeed()
    }

    private fun welcomeLine(name: String): Component =
        Component.empty().withStyle(ChatFormatting.GRAY)
            .append(Component.literal("Welcome "))
            .append(Component.literal(name).withStyle(ChatFormatting.DARK_AQUA))
            .append(Component.literal(" to MCTraveler!"))

    private companion object {
        private val SIGN_AT = BlockPos(3, 2, 3)
    }

    /** Puts a sign in the world that [editor] is the one allowed to write on. */
    private fun GameTestHelper.placeRankTestSign(editor: MessageCapturingPlayer): SignBlockEntity {
        setBlock(SIGN_AT, Blocks.OAK_SIGN)
        val sign = level.getBlockEntity(absolutePos(SIGN_AT)) as SignBlockEntity
        sign.setAllowedPlayerEditor(editor.uuid)
        return sign
    }

    /** Sends the sign text a client sends when the editor presses Done. */
    private fun MessageCapturingPlayer.writesOnRankTestSign(helper: GameTestHelper, firstLine: String) {
        connection.handleSignUpdate(
            ServerboundSignUpdatePacket(helper.absolutePos(SIGN_AT), true, firstLine, "", "", ""),
        )
    }
}
