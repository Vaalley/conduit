package eu.mctraveler.gametest

import eu.mctraveler.rank.Rank
import eu.mctraveler.rank.RankFeature
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionCommands
import eu.mctraveler.region.RegionFlagsMenu
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.util.UUID
import kotlin.math.floor
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ClickEvent
import net.minecraft.server.permissions.LevelBasedPermissionSet

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
        // /rg flags is no longer admin-gated at all (main-page flags are
        // self-service now); locate stays admin-only, and bounds is for Donators too.
        for (command in listOf("rg locate x")) {
            player.runCommand(command)
            helper.assertValueEqual(player.messages.last(), notAdmin, "the non-admin reply to /$command")
        }
        player.leave()
        helper.succeed()
    }

    // ---- /rg flags ----

    @GameTest
    fun rgFlagsOpensTheRegionFlagsGui(helper: GameTestHelper) {
        val admin = adminWithRegion(helper, "T12FlagsOpen")
        try {
            admin.runCommand("rg flags")

            val menu = RegionFlagsMenu.openMenuOf(admin)
            helper.assertTrue(menu != null, "/rg flags opened no menu")
            helper.assertValueEqual(menu!!.page, RegionFlagsMenu.Page.MAIN, "the page /rg flags opens")
            helper.succeed()
        } finally {
            admin.leave()
        }
    }

    @GameTest
    fun aNonAdminResidentCanStillOpenRgFlags(helper: GameTestHelper) {
        // Main-page flags are self-service — residency is enough, no admin
        // status required (unlike the old chat-based /rg flag).
        val resident = MessageCapturingPlayer.join(helper, "T12FlagsRes")
        try {
            createRegion(helper, resident, 0.0 to 0.0, 4.0 to 4.0)
            resident.runCommand("rg flags")

            helper.assertTrue(RegionFlagsMenu.openMenuOf(resident) != null, "a resident could not open /rg flags")
            helper.succeed()
        } finally {
            resident.leave()
        }
    }

    @GameTest
    fun flagAndBoundsCommandsRequireStandingInARegion(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "T12NoReg")
        admin.makeAdmin()
        admin.standAt(helper, 0.0, 1.0, 0.0) // outside every region
        val expectations = listOf(
            "rg flags" to "You must stand in the region you want to view flags for",
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

    // ---- /rg bounds ----

    @GameTest
    fun boundsIsRefusedToAnyoneWhoIsNeitherADonatorNorAnAdmin(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12BndTrav")
        RankFeature.setRank(player, Rank.TRAVELER)
        player.standAt(helper, 0.0, 1.0, 0.0)
        for (command in listOf("rg bounds", "rg bounds 0 100")) {
            player.runCommand(command)
            helper.assertValueEqual(
                player.messages.last(),
                Paint.error("You must be a Donator or an admin to use this command"),
                "the Traveler's reply to /$command",
            )
        }
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun aDonatorSetsAndReadsTheBoundsOfTheirOwnRegion(helper: GameTestHelper) {
        val donator = MessageCapturingPlayer.join(helper, "T12BndDon")
        RankFeature.setRank(donator, Rank.DONATOR)
        val region = createRegion(helper, donator, 0.0 to 0.0, 4.0 to 4.0)
        donator.standAt(helper, 2.0, 100.0, 2.0)

        donator.runCommand("rg bounds 255 15")
        helper.assertValueEqual(
            donator.messages.last(),
            Paint.success(
                "Set Y bounds for ", Paint.green(region.title),
                " to ", Paint.white(15), " - ", Paint.white(255),
            ),
            "the Donator's bounds-set reply",
        )
        donator.runCommand("rg bounds")
        helper.assertValueEqual(
            donator.messages.last(),
            Paint(Paint.green(region.title), " bounds: Y ", Paint.white(15), " to ", Paint.white(255)),
            "the Donator's bounds-show reply",
        )
        donator.leave()
        helper.succeed()
    }

    @GameTest
    fun aDonatorCannotTouchTheBoundsOfARegionThatIsNotTheirs(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "T12BndOwner")
        val donator = MessageCapturingPlayer.join(helper, "T12BndStranger")
        RankFeature.setRank(donator, Rank.DONATOR)
        val region = createRegion(helper, owner, 0.0 to 0.0, 4.0 to 4.0)
        donator.standAt(helper, 2.0, 100.0, 2.0)

        for (command in listOf("rg bounds 255 15", "rg bounds")) {
            donator.runCommand(command)
            helper.assertValueEqual(
                donator.messages.last(),
                Paint.error("You are not a member of this region"),
                "the stranger's reply to /$command",
            )
        }
        helper.assertValueEqual(region.startY, Region.DEFAULT_START_Y, "the region's top after the refused change")
        owner.leave()
        donator.leave()
        helper.succeed()
    }

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
                prefills(Paint.yellow("Qx7Lonely Keep"), 60009, 109),
                " - ", clickableCoordinates(60009, 109),
                "/", Paint.green("overworld"),
            ),
            "the single-result locate reply",
        )
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun locateResultsPrefillAnAdminTeleport(helper: GameTestHelper) {
        insertRegion("Qx7Jump Point", 62000, 100, members = emptyList())
        val admin = MessageCapturingPlayer.join(helper, "T12LocJump")
        admin.makeAdmin()
        admin.runCommand("rg locate qx7jump")

        val click = admin.messages.last().siblings[2].style.clickEvent
        helper.assertTrue(
            click == ClickEvent.SuggestCommand("/execute in minecraft:overworld run tp @s 62009 ~ 109"),
            "the single-result coordinate teleport command was $click",
        )
        PacketCapture.drain(admin)
        // The region name prefills the very same command (a suggestion: nothing runs on the click).
        helper.assertTrue(admin.messages.last().siblings[0].style.clickEvent == click, "the region name does not prefill the same command")
        // Pressing Enter on the prefilled line is what runs it.
        admin.runsClickCommand((click as ClickEvent.SuggestCommand).command().removePrefix("/"))
        helper.runAfterDelay(2) {
            try {
                val teleport = admin.receivesTeleport()
                helper.assertTrue(
                    floor(teleport.change().position().x).toInt() == 62009,
                    "the click teleport packet x was ${teleport.change().position().x}",
                )
                helper.assertTrue(
                    floor(teleport.change().position().z).toInt() == 109,
                    "the click teleport packet z was ${teleport.change().position().z}",
                )
                helper.succeed()
            } finally {
                admin.leave()
            }
        }
    }

    @GameTest
    fun locateFindsRegionsByMemberNameOnlineAndViaTheNameCache(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "Qx8Owner")
        insertRegion("Plain Fields", 61000, 200, members = listOf(owner.uuid))
        val admin = MessageCapturingPlayer.join(helper, "T12LocMem")
        admin.makeAdmin()

        val expected = Paint(
            prefills(Paint.yellow("Plain Fields"), 61009, 209),
            " - ", clickableCoordinates(61009, 209),
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
                    " - ", prefills(Paint.yellow("Zq7Keep%02d".format(i)), 70000 + 200 * (i - 1) + 9, 9), " ",
                    clickableCoordinates(70000 + 200 * (i - 1) + 9, 9, includeY = false),
                    "/", Paint.gray("overworld"),
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

/** Reads the client packet an actual click receives after running its teleport command. */
private fun MessageCapturingPlayer.receivesTeleport(): ClientboundPlayerPositionPacket {
    val packets = PacketCapture.drain(this)
    return checkNotNull(packets.filterIsInstance<ClientboundPlayerPositionPacket>().lastOrNull()) {
        "the teleport command sent no client position packet"
    }
}

/** Runs a click command with the same owner-level permission set a vanilla op has. */
private fun MessageCapturingPlayer.runsClickCommand(command: String) {
    level().server.commands.performPrefixedCommand(
        createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER),
        command,
    )
}

private fun clickableCoordinates(x: Int, z: Int, includeY: Boolean = true): Component {
    val label = if (includeY) "$x/~/$z" else "$x/$z"
    return prefills(Paint.white(label), x, z)
}

/** [label] as a locate result draws it: clicking (and hovering) prefills the teleport to ([x], [z]). */
private fun prefills(label: net.minecraft.network.chat.MutableComponent, x: Int, z: Int): Component =
    label.withStyle(
        net.minecraft.network.chat.Style.EMPTY
            .withClickEvent(ClickEvent.SuggestCommand("/execute in minecraft:overworld run tp @s $x ~ $z"))
            .withHoverEvent(
                net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(RegionCommands.LOCATE_HINT)),
            ),
    )
