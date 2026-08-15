package eu.mctraveler.gametest

import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.util.UUID
import kotlin.math.floor
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component

/**
 * The admin-gated region commands — `/rg flag` (toggle + list), `/rg bounds`
 * (set + show), `/rg locate` — plus the gating itself and the USAGE replies
 * for malformed invocations (deviation 5). Exact messages per the Portal
 * (RegionFeature.ts; inventory §2.8).
 */
class RegionAdminCommandGameTest {

    private val notAdmin = Paint.error("You must be an admin to use this command")

    @GameTest
    fun adminCommandsRefuseNonAdmins(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12Gate")
        player.standAt(helper, 0.0, 1.0, 0.0)
        for (command in listOf("rg flag", "rg flag PUBLIC", "rg bounds", "rg bounds 0 100", "rg locate x")) {
            player.runCommand(command)
            helper.assertValueEqual(player.messages.last(), notAdmin, "the non-admin reply to /$command")
        }
        player.leave()
        helper.succeed()
    }

    // ---- /rg flag ----

    @GameTest
    fun anAdminTogglesAFlagOnAndOff(helper: GameTestHelper) {
        val admin = adminWithRegion(helper, "T12FlagTog")
        admin.runCommand("rg flag public") // lower case: flags are upper-cased
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.success("Flag ", Paint.green("PUBLIC"), " added"),
            "the flag-added reply",
        )
        admin.runCommand("rg flag PUBLIC")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.success("Flag ", Paint.green("PUBLIC"), " removed"),
            "the flag-removed reply",
        )
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun anInvalidFlagListsTheValidFlags(helper: GameTestHelper) {
        val admin = adminWithRegion(helper, "T12FlagBad")
        admin.runCommand("rg flag NOT_A_FLAG")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.error(
                "Invalid flag. Valid flags: EMBASSY, NO_SCOREBOARD, ENABLE_EXPLOSIONS, ADMIN, " +
                    "ENABLE_PUBLIC_CONTAINERS, DISABLE_GATES, ENABLE_FIRE_DAMAGE, " +
                    "DISABLE_PLAYER_FALL_DAMAGE, ENABLE_PUBLIC_VILLAGER_TRADING, " +
                    "DISABLE_PUBLIC_REDSTONE_TRIGGERS, DISABLE_ANIMAL_PROTECTION, PUBLIC",
            ),
            "the invalid-flag reply",
        )
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun theEmbassyFlagCannotBeToggled(helper: GameTestHelper) {
        val admin = adminWithRegion(helper, "T12FlagEmb")
        admin.runCommand("rg flag EMBASSY")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.error("You cannot toggle the embassy flag"),
            "the embassy-toggle reply",
        )
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun flagAndBoundsCommandsRequireStandingInARegion(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "T12NoReg")
        admin.makeAdmin()
        admin.standAt(helper, 0.0, 1.0, 0.0) // outside every region
        val expectations = listOf(
            "rg flag PUBLIC" to "You must stand in the region you want to toggle a flag on",
            "rg flag" to "You must stand in a region to view flags",
            "rg bounds 0 100" to "You must stand in the region you want to set bounds for",
            "rg bounds" to "You must stand in a region to view bounds",
        )
        for ((command, message) in expectations) {
            admin.runCommand(command)
            helper.assertValueEqual(admin.messages.last(), Paint.error(message), "the reply to /$command")
        }
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun flagListShowsEnabledGreenThenDisabledRed(helper: GameTestHelper) {
        val admin = adminWithRegion(helper, "T12FlagList")
        admin.runCommand("rg flag NO_SCOREBOARD")
        admin.runCommand("rg flag PUBLIC")
        admin.runCommand("rg flag")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.gray(
                "Flags: ",
                Paint.green("NO_SCOREBOARD"), ", ", Paint.green("PUBLIC"), ", ",
                Paint.red("EMBASSY"), ", ", Paint.red("ENABLE_EXPLOSIONS"), ", ",
                Paint.red("ADMIN"), ", ", Paint.red("ENABLE_PUBLIC_CONTAINERS"), ", ",
                Paint.red("DISABLE_GATES"), ", ", Paint.red("ENABLE_FIRE_DAMAGE"), ", ",
                Paint.red("DISABLE_PLAYER_FALL_DAMAGE"), ", ", Paint.red("ENABLE_PUBLIC_VILLAGER_TRADING"), ", ",
                Paint.red("DISABLE_PUBLIC_REDSTONE_TRIGGERS"), ", ", Paint.red("DISABLE_ANIMAL_PROTECTION"),
            ),
            "the flag list",
        )
        admin.leave()
        helper.succeed()
    }

    // ---- /rg bounds ----

    @GameTest
    fun boundsRejectsOutOfRangeAndThinSpans(helper: GameTestHelper) {
        val admin = adminWithRegion(helper, "T12BndBad")
        admin.runCommand("rg bounds -70 100")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.error("Y bounds must be between -64 and 320"),
            "the out-of-range bounds reply",
        )
        admin.runCommand("rg bounds 0 10")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.error("Y bounds must be at least 16 blocks tall"),
            "the thin-span bounds reply",
        )
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun boundsSetAndShowRoundTrip(helper: GameTestHelper) {
        val admin = adminWithRegion(helper, "T12BndSet")
        // Arguments land min/max normalised, whichever order they came in.
        admin.runCommand("rg bounds 255 15")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.success(
                "Set Y bounds for ", Paint.green("T12BndSet's Place"),
                " to ", Paint.white(15), " - ", Paint.white(255),
            ),
            "the bounds-set reply",
        )
        // The new bounds are real: standing below y 15 is now outside the
        // region, so step up inside it before asking for the bounds.
        admin.setPos(admin.x, 100.0, admin.z)
        admin.runCommand("rg bounds")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint(
                Paint.green("T12BndSet's Place"),
                " bounds: Y ", Paint.white(15), " to ", Paint.white(255),
            ),
            "the bounds-show reply",
        )
        // And the bounds actually apply: above the new top there is no region.
        val service = RegionsFeature.requireService()
        val x = floor(admin.x).toInt()
        val z = floor(admin.z).toInt()
        check(service.regionAt("world", x, 300, z) == null) { "y 300 should now be outside the region" }
        check(service.regionAt("world", x, 100, z) != null) { "y 100 should still be inside the region" }
        admin.leave()
        helper.succeed()
    }

    // ---- /rg locate ----
    //
    // The Portal printed `server/dimension` here, and so did this port until the
    // merge: a Region genuinely lived on one of two backend servers. There is one
    // map now, so the server half named something that does not exist and was
    // removed (merge spec, User Story 25). The dimension half is unchanged, and
    // is what these cases pin.

    @GameTest
    fun locateFindsARegionByTitleSubstring(helper: GameTestHelper) {
        insertRegion("Qx7Lonely Keep", 60000, 100, members = emptyList())
        val admin = MessageCapturingPlayer.join(helper, "T12LocOne")
        admin.makeAdmin()
        admin.runCommand("rg locate qx7lone")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint(
                Paint.yellow("Qx7Lonely Keep"),
                " - ", Paint.white("60009/~/109"),
                "/", Paint.green("overworld"),
            ),
            "the single-result locate reply",
        )
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun locateFindsRegionsByMemberNameOnlineAndViaTheNameCache(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "Qx8Owner")
        insertRegion("Plain Fields", 61000, 200, members = listOf(owner.uuid))
        val admin = MessageCapturingPlayer.join(helper, "T12LocMem")
        admin.makeAdmin()

        val expected = Paint(
            Paint.yellow("Plain Fields"),
            " - ", Paint.white("61009/~/209"),
            "/", Paint.green("overworld"),
        )
        admin.runCommand("rg locate qx8own")
        helper.assertValueEqual(admin.messages.last(), expected, "the member-name locate reply")

        // With the member offline, the name cache still answers (deviation 10).
        owner.leave()
        admin.runCommand("rg locate qx8own")
        helper.assertValueEqual(admin.messages.last(), expected, "the offline member-name locate reply")
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun locateListsMultipleMatchesWithACountHeaderAndTruncation(helper: GameTestHelper) {
        for (i in 1..12) {
            insertRegion("Zq7Keep%02d".format(i), 70000 + 200 * (i - 1), 0, members = emptyList())
        }
        val admin = MessageCapturingPlayer.join(helper, "T12LocMany")
        admin.makeAdmin()
        val before = admin.messages.size
        admin.runCommand("rg locate zq7keep")

        val pageHeader = Paint.gray("[").append(
            Paint.yellow.runs("/rg locate zq7keep 2")("Page 1/2")
        ).append(Paint.gray("]:"))
        val expected = mutableListOf<Component>(Paint("Located regions (", Paint.yellow(12), ") ", pageHeader))
        for (i in 1..10) {
            expected.add(
                Paint(
                    " - ", Paint.yellow("Zq7Keep%02d".format(i)), " ",
                    Paint.gray("${70000 + 200 * (i - 1) + 9}/9/overworld"),
                ),
            )
        }
        expected.add(
            Paint(
                Paint.darkGray("[< Prev]"),
                " ",
                Paint.gray("Page 1/2"),
                " ",
                Paint.yellow.bold.runs("/rg locate zq7keep 2")("[Next >]"),
            ),
        )
        helper.assertValueEqual(admin.messages.drop(before), expected.toList(), "the multi-result locate replies")
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun locateWithNoMatchErrors(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "T12LocNone")
        admin.makeAdmin()
        admin.runCommand("rg locate qqqqzzzz")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.error("No regions found matching \"qqqqzzzz\""),
            "the no-match locate reply",
        )
        admin.leave()
        helper.succeed()
    }

    // ---- usage errors (deviation 5) ----

    @GameTest
    fun malformedSubcommandsGetUsageReplies(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12Usage")
        val expectations = listOf(
            "rg rename" to "/rg rename <name>",
            "region rename" to "/region rename <name>",
            "rg locate" to "/rg locate <name>",
            "rg bounds 5" to "/rg bounds <min-y> <max-y>",
        )
        for ((command, usage) in expectations) {
            player.runCommand(command)
            helper.assertValueEqual(player.messages.last(), Paint.usage(usage), "the reply to /$command")
        }
        player.leave()
        helper.succeed()
    }

    // ---- shared steps ----

    /** An Admin standing inside a region of their own within the structure. */
    private fun adminWithRegion(helper: GameTestHelper, name: String): MessageCapturingPlayer {
        val admin = MessageCapturingPlayer.join(helper, name)
        admin.makeAdmin()
        admin.standAt(helper, 0.0, 1.0, 0.0)
        admin.runCommand("rg start")
        admin.standAt(helper, 7.0, 1.0, 1.0)
        admin.runCommand("rg end")
        admin.standAt(helper, 3.0, 1.0, 0.0)
        return admin
    }

    /**
     * Plants a 20×20 region at fixed far-away coordinates, straight into the
     * service — `/rg locate` output needs known literal coordinates, and far
     * placement keeps it clear of every test structure.
     */
    private fun insertRegion(title: String, x: Int, z: Int, members: List<UUID>) {
        val region = Region(
            title = title,
            world = "world",
            startX = x, startZ = z, endX = x + 19, endZ = z + 19,
        )
        region.members.addAll(members)
        RegionsFeature.requireService().add(region, parent = null)
    }
}
