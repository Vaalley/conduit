package eu.mctraveler.gametest

import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import kotlin.math.floor
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.level.Level

/**
 * The region lifecycle commands — `/rg` help, `/rg start`/`/rg end` with the
 * Portal's full validation sequence, `/rg rename`, `/rg delete` — asserting
 * the exact messages a player sees (RegionFeature.ts; inventory §2.8).
 * Admin-gated commands are in [RegionAdminCommandGameTest].
 */
class RegionCommandGameTest {

    // ---- help panel ----

    private val helpPanel: Component = Paint(
        Paint.darkGray("--["), " ", Paint.green.bold("Region Commands"), " ", Paint.darkGray("]--"), "\n",
        Paint.gray(" - "), Paint.white("/rg rename <name>"), "\n",
        Paint.gray(" - "), Paint.white("/rg add <player>"), "\n",
        Paint.gray(" - "), Paint.white("/rg remove <player>"), "\n",
        Paint.gray(" - "), Paint.white("/rg delete"), "\n",
        Paint.gray(" - "), Paint.white("/rg start"), " ", Paint.gray("+ "), Paint.white("/rg end"), "\n",
        Paint.gray(" - "), Paint.white("/rg extend <distance>"), "\n",
        Paint.gray(" - "), Paint.white("/rg flags"), "\n",
        Paint.gray(" - "), Paint.white("/rg locate <name>"),
    )

    @GameTest
    fun bareRgShowsTheHelpPanel(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12Help")
        player.runCommand("rg")
        helper.assertValueEqual(player.messages.last(), helpPanel, "the /rg help panel")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun theRegionAliasShowsTheSameHelpPanel(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12Help2")
        player.runCommand("region")
        helper.assertValueEqual(player.messages.last(), helpPanel, "the /region help panel")
        player.leave()
        helper.succeed()
    }

    // ---- /rg start ----

    @GameTest
    fun rgStartSetsTheFirstPointWithTheFollowUpHint(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12Start")
        player.standAt(helper, 1.0, 1.0, 1.0)
        player.runCommand("rg start")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.success("First point set!\n\nNow move over to the next point and do:\n", Paint.green("/rg end")),
            "the /rg start reply",
        )
        player.leave()
        helper.succeed()
    }

    // ---- /rg end validation sequence ----

    @GameTest
    fun rgEndWithoutStartErrors(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12NoStart")
        player.runCommand("rg end")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("You must start first. Use /rg start"),
            "the /rg end without /rg start reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun rgEndInAnotherDimensionGetsTheSameWorldError(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12Dim")
        player.standAt(helper, 0.0, 1.0, 0.0)
        player.runCommand("rg start")
        val nether = checkNotNull(helper.level.server.getLevel(Level.NETHER))
        player.setServerLevel(nether)
        player.runCommand("rg end")
        player.setServerLevel(helper.level)
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("Regions may only be created in the same world."),
            "the cross-dimension /rg end reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun rgEndTooCloseErrorsRegionTooSmall(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12Small")
        player.standAt(helper, 1.0, 1.0, 1.0)
        player.runCommand("rg start")
        player.standAt(helper, 3.0, 1.0, 3.0) // 3×3 = 9 blocks: one short
        player.runCommand("rg end")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("Region too small"),
            "the too-small /rg end reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun rgEndTooLargeWithoutAdminReportsTheBlockCount(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12Large")
        player.standAt(helper, 0.0, 1.0, 0.0)
        player.runCommand("rg start")
        player.standAt(helper, 100.0, 1.0, 50.0) // 101×51 = 5151 blocks
        player.runCommand("rg end")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("Region too large (5151 blocks). Limit is 5000 blocks. Ask an admin to create it."),
            "the too-large /rg end reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun anAdminMayCreateARegionAboveTheSizeLimit(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12BigAdmin")
        player.makeAdmin()
        // Far from every test structure: admin-sized regions are real and stay.
        player.standAt(helper, 30000.0, 1.0, 0.0)
        player.runCommand("rg start")
        player.standAt(helper, 30100.0, 1.0, 50.0)
        player.runCommand("rg end")
        helper.assertValueEqual(
            player.messages.last(),
            createdReply("T12BigAdmin's Place"),
            "the admin oversized /rg end reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun rgEndCreatesTheRegionWithFullBuildHeight(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12Create")
        player.standAt(helper, 0.0, 1.0, 0.0)
        player.runCommand("rg start")
        player.standAt(helper, 7.0, 1.0, 1.0) // 8×2 = 16 blocks
        player.runCommand("rg end")
        helper.assertValueEqual(
            player.messages.last(),
            createdReply("T12Create's Place"),
            "the /rg end creation reply",
        )

        // The region protects the full build height (deviation 2)…
        val service = RegionsFeature.requireService()
        val x = floor(player.x).toInt()
        val z = floor(player.z).toInt()
        val region = checkNotNull(service.regionAt("world", x, 320, z)) { "no region at y 320" }
        helper.assertValueEqual(region.title, "T12Create's Place", "created region title")
        helper.assertValueEqual(region.startY, 320, "created region top y")
        helper.assertValueEqual(region.endY, -64, "created region bottom y")
        check(service.regionAt("world", x, -64, z) === region) { "no region at y -64" }

        // …and the start marker is spent: /rg end again must ask to start.
        player.runCommand("rg end")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("You must start first. Use /rg start"),
            "the /rg end reply after the marker was spent",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun rgEndRefusesOverlapWithOneCornerInsideAnExistingRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12OvlA")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        val bob = MessageCapturingPlayer.join(helper, "T12OvlB")
        bob.standAt(helper, 0.0, 1.0, 0.0) // inside Alice's region
        bob.runCommand("rg start")
        bob.standAt(helper, 7.0, 1.0, 5.0) // outside it
        bob.runCommand("rg end")
        helper.assertValueEqual(
            bob.messages.last(),
            Paint.error("Overlapping region ", Paint.red("T12OvlA's Place"), "!"),
            "the overlapping /rg end reply",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun rgEndRefusesACrossingStripEvenWithNoCornerInside(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12StripA")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        // A strip crossing straight through: no corner of either rectangle
        // lies inside the other — the Portal missed this (deviation 3).
        val bob = MessageCapturingPlayer.join(helper, "T12StripB")
        bob.standAt(helper, 2.0, 1.0, -4.0)
        bob.runCommand("rg start")
        bob.standAt(helper, 5.0, 1.0, 5.0)
        bob.runCommand("rg end")
        helper.assertValueEqual(
            bob.messages.last(),
            Paint.error("Overlapping region ", Paint.red("T12StripA's Place"), "!"),
            "the crossing-strip /rg end reply",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    // ---- sub-region rules ----

    @GameTest
    fun aResidentMayCreateASubRegionInsideTheirRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12SubA")
        val parent = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 7.0)

        alice.standAt(helper, 0.0, 1.0, 0.0)
        alice.runCommand("rg start")
        alice.standAt(helper, 4.0, 1.0, 1.0) // 5×2 = 10 blocks, fully inside
        alice.runCommand("rg end")
        helper.assertValueEqual(
            alice.messages.last(),
            createdReply("T12SubA's Place"),
            "the sub-region /rg end reply",
        )

        val sub = parent.subRegions.single()
        check(sub.parent === parent) { "sub-region not wired to its parent" }
        // Deepest match wins where they nest.
        val service = RegionsFeature.requireService()
        check(service.regionAt(parent.world, sub.minX, 1, sub.minZ) === sub) {
            "lookup inside the sub-region did not resolve to the sub-region"
        }
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonResidentCannotCreateASubRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12ParA")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 7.0)

        val bob = MessageCapturingPlayer.join(helper, "T12ParB")
        bob.standAt(helper, 0.0, 1.0, 0.0)
        bob.runCommand("rg start")
        bob.standAt(helper, 4.0, 1.0, 1.0)
        bob.runCommand("rg end")
        helper.assertValueEqual(
            bob.messages.last(),
            Paint.error("You are not a member of the parent region"),
            "the non-resident sub-region /rg end reply",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun noSubRegionsInsideAnEmbassy(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12EmbA")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 7.0)
        // EMBASSY comes only from legacy data — no command can set it.
        region.flags.add("EMBASSY")
        RegionsFeature.requireService().save()

        alice.standAt(helper, 0.0, 1.0, 0.0)
        alice.runCommand("rg start")
        alice.standAt(helper, 4.0, 1.0, 1.0)
        alice.runCommand("rg end")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.error("You cannot create a region inside an embassy"),
            "the embassy sub-region /rg end reply",
        )
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun noSubRegionsInsideAnAdminFlaggedRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12AdmA")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 7.0)

        val admin = MessageCapturingPlayer.join(helper, "T12AdmOp")
        admin.makeAdmin()
        admin.standAt(helper, 3.0, 1.0, 3.0)
        admin.setFlag("ADMIN", on = true)

        // Even the resident cannot nest inside an ADMIN-flagged region.
        alice.standAt(helper, 0.0, 1.0, 0.0)
        alice.runCommand("rg start")
        alice.standAt(helper, 4.0, 1.0, 1.0)
        alice.runCommand("rg end")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.error("You cannot create a region inside a region with admin flag"),
            "the admin-flag sub-region /rg end reply",
        )
        alice.leave()
        admin.leave()
        helper.succeed()
    }

    // ---- /rg rename ----

    @GameTest
    fun renameOutsideAnyRegionErrors(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12RenOut")
        player.standAt(helper, 0.0, 1.0, 0.0)
        player.runCommand("rg rename New Name")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("You must stand in the region you want to rename"),
            "the /rg rename outside-region reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberCannotRename(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12RenA")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        val bob = MessageCapturingPlayer.join(helper, "T12RenB")
        bob.standAt(helper, 3.0, 1.0, 0.0)
        bob.runCommand("rg rename Stolen Name")
        helper.assertValueEqual(
            bob.messages.last(),
            Paint.error("You are not a member of this region"),
            "the non-member /rg rename reply",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun renameRejectsAnInvalidName(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12RenBad")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        val invalidName = Paint.error("Invalid region name")
        alice.runCommand("rg rename ab") // under 3 characters
        helper.assertValueEqual(alice.messages.last(), invalidName, "the too-short name reply")
        alice.runCommand("rg rename Bad~Name") // '~' is outside the alphabet
        helper.assertValueEqual(alice.messages.last(), invalidName, "the illegal-character name reply")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentRenamesTheirRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12RenMe")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        alice.runCommand("rg rename Alice's #1 (Best) Place!")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.success(
                "Renamed ", Paint.green("T12RenMe's Place"),
                " to ", Paint.green("Alice's #1 (Best) Place!"),
            ),
            "the /rg rename reply",
        )
        helper.assertValueEqual(region.title, "Alice's #1 (Best) Place!", "renamed region title")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun anAdminMayRenameAnotherPlayersRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12RenOwn")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        val admin = MessageCapturingPlayer.join(helper, "T12RenOp")
        admin.makeAdmin()
        admin.standAt(helper, 3.0, 1.0, 0.0)
        admin.runCommand("rg rename Renamed By Admin")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.success(
                "Renamed ", Paint.green("T12RenOwn's Place"),
                " to ", Paint.green("Renamed By Admin"),
            ),
            "the admin /rg rename reply",
        )
        alice.leave()
        admin.leave()
        helper.succeed()
    }

    // ---- /rg delete ----

    @GameTest
    fun deleteOutsideAnyRegionErrors(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12DelOut")
        player.standAt(helper, 0.0, 1.0, 0.0)
        player.runCommand("rg delete")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("You must stand in the region you want to delete"),
            "the /rg delete outside-region reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberCannotDelete(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12DelA")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        val bob = MessageCapturingPlayer.join(helper, "T12DelB")
        bob.standAt(helper, 3.0, 1.0, 0.0)
        bob.runCommand("rg delete")
        helper.assertValueEqual(
            bob.messages.last(),
            Paint.error("You are not a member of this region"),
            "the non-member /rg delete reply",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun deletingAnEmbassyIsRefused(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12DelEmb")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)
        region.flags.add("EMBASSY") // legacy data only — no command sets it
        RegionsFeature.requireService().save()

        alice.runCommand("rg delete")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.error(
                "You must use ",
                Paint.red.runs("/embassy delete")("/embassy delete"),
                " to delete an embassy",
            ),
            "the embassy /rg delete reply",
        )
        // The way out is one click away (story 19).
        val refusal = alice.messages.last()
        val command = refusal.toFlatList(refusal.style).first { it.string == "/embassy delete" }
        helper.assertValueEqual(
            checkNotNull(command.style.clickEvent) { "the /embassy delete pointer was not clickable" },
            ClickEvent.RunCommand("/embassy delete"),
            "the /embassy delete click event",
        )
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentDeletesTheirRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12DelMe")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        alice.runCommand("rg delete")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.success("Deleted region ", Paint.green("T12DelMe's Place")),
            "the /rg delete reply",
        )
        val service = RegionsFeature.requireService()
        check(service.regionAt(region.world, region.minX, 1, region.minZ) == null) {
            "the deleted region is still there"
        }
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun deletingASubRegionDetachesItFromItsParent(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12DelSub")
        val parent = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 7.0)
        alice.standAt(helper, 0.0, 1.0, 0.0)
        alice.runCommand("rg start")
        alice.standAt(helper, 4.0, 1.0, 1.0)
        alice.runCommand("rg end")
        val sub = parent.subRegions.single()

        // Standing inside the sub-region: the deepest match is what deletes.
        alice.standAt(helper, 2.0, 1.0, 1.0)
        alice.runCommand("rg delete")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.success("Deleted region ", Paint.green("T12DelSub's Place")),
            "the sub-region /rg delete reply",
        )
        check(parent.subRegions.isEmpty()) { "the sub-region is still attached to its parent" }
        check(sub.parent == null) { "the deleted sub-region still points at its parent" }
        val service = RegionsFeature.requireService()
        check(service.regionAt(parent.world, sub.minX, 1, sub.minZ) === parent) {
            "the parent no longer answers where its sub-region was"
        }
        alice.leave()
        helper.succeed()
    }

    // ---- /rg extend ----

    @GameTest
    fun bareRgExtendShowsUsage(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12ExtUse")
        player.runCommand("rg extend")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.usage("/rg extend <distance>"),
            "the bare /rg extend reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun extendOutsideAnyRegionErrors(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12ExtOut")
        player.standAt(helper, 0.0, 1.0, 0.0)
        player.face(Direction.SOUTH)
        player.runCommand("rg extend 3")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("You must stand in the region you want to extend"),
            "the /rg extend outside-region reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberCannotExtend(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12ExtNmA")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 3.0)

        val bob = MessageCapturingPlayer.join(helper, "T12ExtNmB")
        bob.standAt(helper, 3.0, 1.0, 1.0)
        bob.face(Direction.SOUTH)
        bob.runCommand("rg extend 2")
        helper.assertValueEqual(
            bob.messages.last(),
            Paint.error("You are not a member of this region"),
            "the non-member /rg extend reply",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun extendRejectsZeroOrNegativeDistance(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12ExtNeg")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 3.0)
        alice.standAt(helper, 3.0, 1.0, 1.0)
        alice.face(Direction.SOUTH)

        val usage = Paint.usage("/rg extend <distance>")
        alice.runCommand("rg extend 0")
        helper.assertValueEqual(alice.messages.last(), usage, "the /rg extend 0 reply")
        alice.runCommand("rg extend -4")
        helper.assertValueEqual(alice.messages.last(), usage, "the /rg extend -4 reply")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentExtendsTheEdgeTheyFace(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12ExtMe")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)
        val southEdge = region.maxZ
        val westEdge = region.minX

        alice.standAt(helper, 3.0, 1.0, 0.0)
        alice.face(Direction.SOUTH)
        alice.runCommand("rg extend 3")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.success(
                "Extended ", Paint.green("T12ExtMe's Place"),
                " ", Paint.white(3), " blocks ", Paint.white("south"),
            ),
            "the southward /rg extend reply",
        )
        helper.assertValueEqual(region.maxZ, southEdge + 3, "the region's south edge moved out 3")

        alice.face(Direction.WEST)
        alice.runCommand("rg extend 2")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.success(
                "Extended ", Paint.green("T12ExtMe's Place"),
                " ", Paint.white(2), " blocks ", Paint.white("west"),
            ),
            "the westward /rg extend reply",
        )
        helper.assertValueEqual(region.minX, westEdge - 2, "the region's west edge moved out 2")

        // The grown corner answers as the region now, live.
        val service = RegionsFeature.requireService()
        check(service.regionAt("world", region.minX, 1, region.maxZ) === region) {
            "the extended corner is not the region"
        }
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun extendRefusesToOverlapAnotherRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12ExtOvA")
        val region = createRegion(helper, alice, 0.0 to 0.0, 5.0 to 3.0)
        val southEdge = region.maxZ

        val bob = MessageCapturingPlayer.join(helper, "T12ExtOvB")
        createRegion(helper, bob, 0.0 to 6.0, 5.0 to 9.0)

        alice.standAt(helper, 2.0, 1.0, 1.0)
        alice.face(Direction.SOUTH)
        alice.runCommand("rg extend 10")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.error("Overlapping region ", Paint.red("T12ExtOvB's Place"), "!"),
            "the overlapping /rg extend reply",
        )
        helper.assertValueEqual(region.maxZ, southEdge, "the refused region kept its edge")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aSubRegionCannotExtendOutsideItsParent(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T12ExtSub")
        val parent = createRegion(helper, alice, 0.0 to 0.0, 9.0 to 9.0)
        alice.standAt(helper, 1.0, 1.0, 1.0)
        alice.runCommand("rg start")
        alice.standAt(helper, 4.0, 1.0, 4.0)
        alice.runCommand("rg end")
        val sub = parent.subRegions.single()
        val southEdge = sub.maxZ

        alice.standAt(helper, 2.0, 1.0, 2.0) // inside the sub-region
        alice.face(Direction.SOUTH)
        alice.runCommand("rg extend 20")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.error("You cannot extend a region outside ", Paint.red("T12ExtSub's Place")),
            "the sub-region past-parent /rg extend reply",
        )
        helper.assertValueEqual(sub.maxZ, southEdge, "the refused sub-region kept its edge")

        // Within the parent it is allowed.
        alice.runCommand("rg extend 3")
        helper.assertValueEqual(sub.maxZ, southEdge + 3, "the sub-region extended inside its parent")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun extendRefusesToCrossTheSizeLimit(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T12ExtCap")
        // 99 × 50 = 4950 blocks: one small step from the limit. Far from every
        // other test structure, like the oversized /rg end regions.
        val region = createRegion(helper, player, 32000.0 to 0.0, 32098.0 to 49.0)
        val southEdge = region.maxZ

        player.standAt(helper, 32050.0, 1.0, 25.0)
        player.face(Direction.SOUTH)
        player.runCommand("rg extend 2") // 99 × 52 = 5148
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("Region too large (5148 blocks). Limit is 5000 blocks. Ask an admin to extend it further."),
            "the over-the-limit /rg extend reply",
        )
        helper.assertValueEqual(region.maxZ, southEdge, "the refused region kept its edge")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun anAdminMayExtendPastTheSizeLimit(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "T12ExtCapOp")
        admin.makeAdmin()
        val region = createRegion(helper, admin, 33000.0 to 0.0, 33098.0 to 49.0)
        val southEdge = region.maxZ

        admin.standAt(helper, 33050.0, 1.0, 25.0)
        admin.face(Direction.SOUTH)
        admin.runCommand("rg extend 100") // 99 × 150 = 14850
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.success(
                "Extended ", Paint.green("T12ExtCapOp's Place"),
                " ", Paint.white(100), " blocks ", Paint.white("south"),
            ),
            "the admin over-the-limit /rg extend reply",
        )
        helper.assertValueEqual(region.maxZ, southEdge + 100, "the admin region's edge moved out 100")
        admin.leave()
        helper.succeed()
    }

    // ---- shared steps ----

    /** The exact `/rg end` success reply for a region titled [title]. */
    private fun createdReply(title: String): Component = Paint.success(
        "Region ", Paint.green(title),
        " created!\n\nYou can now rename the region:\n", Paint.green("/rg rename <name>"),
    )
}
