package eu.mctraveler.gametest

import eu.mctraveler.rank.Rank
import eu.mctraveler.rank.RankFeature
import java.util.Optional
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.scores.TeamColor

/**
 * `/locator-color` (issue #84): a Donator's own colour on the locator bar, stored in vanilla's
 * waypoint icon — so it rides along in the player's save, and across death, like any other
 * player data.
 */
class LocatorColorGameTest {

    private fun donator(helper: GameTestHelper, name: String): MessageCapturingPlayer {
        val player = MessageCapturingPlayer.join(helper, name)
        RankFeature.setRank(player, Rank.DONATOR)
        return player
    }

    @GameTest
    fun aDonatorSetsAMinecraftColourByName(helper: GameTestHelper) {
        val player = donator(helper, "LocDonName")
        player.runCommand("locator-color gold")
        helper.assertValueEqual(
            player.waypointIcon().color,
            Optional.of(TeamColor.GOLD.rgb()),
            "the locator colour after /locator-color gold",
        )
        player.runCommand("locator-color Light_Purple")
        helper.assertValueEqual(
            player.waypointIcon().color,
            Optional.of(TeamColor.LIGHT_PURPLE.rgb()),
            "names are not case sensitive",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun aDonatorSetsAHexColour(helper: GameTestHelper) {
        val player = donator(helper, "LocDonHex")
        for ((input, expected) in listOf("#ff8800" to 0xFF8800, "00aaff" to 0x00AAFF, "#f80" to 0xFF8800, "0af" to 0x00AAFF)) {
            player.runCommand("locator-color $input")
            helper.assertValueEqual(player.waypointIcon().color, Optional.of(expected), "the colour after '$input'")
        }
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun aBadColourChangesNothingAndSaysWhy(helper: GameTestHelper) {
        val player = donator(helper, "LocDonBad")
        player.runCommand("locator-color gold")
        for (bad in listOf("notacolour", "#ff88", "#gggggg", "12345678")) {
            player.messages.clear()
            player.runCommand("locator-color $bad")
            helper.assertValueEqual(
                player.waypointIcon().color,
                Optional.of(TeamColor.GOLD.rgb()),
                "the colour after the bad input '$bad'",
            )
            helper.assertTrue(
                player.messages.last().string.startsWith("ERROR"),
                "'$bad' was answered: ${player.messages.last().string}",
            )
        }
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun resetPutsTheDefaultBack(helper: GameTestHelper) {
        val player = donator(helper, "LocDonReset")
        player.runCommand("locator-color red")
        player.runCommand("locator-color reset")
        helper.assertValueEqual(player.waypointIcon().color, Optional.empty<Int>(), "the colour after reset")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun onlyDonatorsAndAdminsMayUseIt(helper: GameTestHelper) {
        val traveler = MessageCapturingPlayer.join(helper, "LocTraveler")
        RankFeature.setRank(traveler, Rank.TRAVELER)
        traveler.messages.clear()
        traveler.runCommand("locator-color gold")
        helper.assertValueEqual(traveler.waypointIcon().color, Optional.empty<Int>(), "a Traveler's colour")
        helper.assertTrue(
            traveler.messages.last().string.contains("Only Donators"),
            "a Traveler was answered: ${traveler.messages.last().string}",
        )

        val admin = MessageCapturingPlayer.join(helper, "LocAdmin")
        RankFeature.setRank(admin, Rank.TRAVELER)
        admin.makeAdmin()
        admin.runCommand("locator-color aqua")
        helper.assertValueEqual(
            admin.waypointIcon().color,
            Optional.of(TeamColor.AQUA.rgb()),
            "an admin's colour",
        )
        traveler.leave()
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun theColourIsOnlyEverTheirOwn(helper: GameTestHelper) {
        val donator = donator(helper, "LocDonSelf")
        val other = MessageCapturingPlayer.join(helper, "LocOther")
        donator.runCommand("locator-color blue")
        helper.assertValueEqual(other.waypointIcon().color, Optional.empty<Int>(), "another player's colour")
        donator.leave()
        other.leave()
        helper.succeed()
    }

    @GameTest
    fun theColourIsWrittenToTheSaveAndSurvivesDeath(helper: GameTestHelper) {
        val player = donator(helper, "LocDonSave")
        player.runCommand("locator-color #ff8800")

        // Restarts and reconnects: the game writes the icon into the player's save.
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
        player.saveWithoutId(output)
        val icon = output.buildResult().getCompoundOrEmpty("locator_bar_icon")
        helper.assertTrue(icon.contains("color"), "the save has no locator colour: $icon")

        // Death: a respawned player is a new object that takes the old one's icon over.
        val reborn = MessageCapturingPlayer.join(helper, "LocDonReborn")
        reborn.restoreFrom(player, false)
        helper.assertValueEqual(
            reborn.waypointIcon().color,
            Optional.of(0xFF8800),
            "the colour of the player who respawned",
        )
        player.leave()
        reborn.leave()
        helper.succeed()
    }
}
