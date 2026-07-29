package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component

/**
 * The region sidebar scoreboard at the running-server seam (spec story 33):
 * what a player's client is actually told to render as they enter, leave and
 * move between regions, and as membership changes underneath them
 * (RegionFeature.ts; inventory §2.8).
 *
 * Every coordinate here stays within a few blocks of the test structure: the
 * gametest batch lays structures out roughly 15 blocks apart, so a player who
 * strays further can wander into a neighbouring test's region.
 */
class RegionScoreboardGameTest {

    private val separator: Component = Paint.darkGray.strikethrough(" ".repeat(30))
    private val residents: Component = Paint.bold("Residents")

    private fun heading(title: String): Component = Paint.green.bold(title)

    // ---- entering and leaving ----

    @GameTest
    fun walkingIntoARegionShowsItsSidebar(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SbAlice")
        val bob = MessageCapturingPlayer.join(helper, "T13SbBob")
        createRegion(helper, alice, 0.0 to 0.0, 3.0 to 2.0)
        bob.standAt(helper, 0.0, 1.0, 7.0) // outside
        val view = SidebarView(bob)

        helper.runAfterDelay(2) {
            helper.assertFalse(view.refresh().visible, "the sidebar showed outside every region")
            bob.standAt(helper, 1.0, 1.0, 1.0) // inside Alice's region
        }
        helper.runAfterDelay(4) {
            view.refresh()
            helper.assertTrue(view.visible, "no sidebar after walking into a region")
            helper.assertValueEqual(view.title, heading("T13SbAlice's Place"), "the sidebar heading")
            helper.assertValueEqual(
                view.lines,
                listOf(separator, residents, Paint.gray("T13SbAlice")),
                "the sidebar lines seen by a non-member",
            )
            alice.leave()
            bob.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun leavingARegionHidesTheSidebar(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SbOut")
        createRegion(helper, alice, 0.0 to 0.0, 3.0 to 2.0)
        val view = SidebarView(alice)

        helper.runAfterDelay(2) {
            helper.assertTrue(view.refresh().visible, "no sidebar inside the region")
            alice.standAt(helper, 0.0, 1.0, 7.0)
        }
        helper.runAfterDelay(4) {
            view.refresh()
            helper.assertFalse(view.visible, "the sidebar survived leaving the region")
            helper.assertValueEqual(view.lines, emptyList<Component>(), "the sidebar lines after leaving")
            alice.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun theObjectiveIsOnlyEverCreatedOncePerSession(helper: GameTestHelper) {
        // A client refuses a second objective of the same name, so entering a
        // region twice must not re-create it (the Portal's per-connection
        // re-create trick existed only to survive backend server switches).
        val alice = MessageCapturingPlayer.join(helper, "T13SbOnce")
        createRegion(helper, alice, 0.0 to 0.0, 3.0 to 2.0)
        val view = SidebarView(alice)

        helper.runAfterDelay(2) { alice.standAt(helper, 0.0, 1.0, 7.0) }
        helper.runAfterDelay(4) { alice.standAt(helper, 1.0, 1.0, 1.0) }
        helper.runAfterDelay(6) {
            view.refresh()
            helper.assertTrue(view.visible, "no sidebar after re-entering the region")
            helper.assertValueEqual(view.objectiveAdditions, 1, "the number of objective-create packets")
            alice.leave()
            helper.succeed()
        }
    }

    // ---- who is who ----

    @GameTest
    fun membersSeeThemselvesInWhiteAndEachOtherInGray(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SbSelfA")
        val bob = MessageCapturingPlayer.join(helper, "T13SbSelfB")
        createRegion(helper, alice, 0.0 to 0.0, 3.0 to 2.0)
        alice.runCommand("rg add T13SbSelfB")
        bob.standAt(helper, 1.0, 1.0, 1.0)
        val aliceView = SidebarView(alice)
        val bobView = SidebarView(bob)

        helper.runAfterDelay(2) {
            helper.assertValueEqual(
                aliceView.refresh().lines,
                listOf(separator, residents, Paint.gray("T13SbSelfB"), Paint.white("T13SbSelfA")),
                "the owner's own sidebar",
            )
            helper.assertValueEqual(
                bobView.refresh().lines,
                listOf(separator, residents, Paint.white("T13SbSelfB"), Paint.gray("T13SbSelfA")),
                "the second member's sidebar",
            )
            alice.leave()
            bob.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun longNamesAreTruncatedAndUnknownMembersAreSkipped(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SbLong")
        val region = createRegion(helper, alice, 0.0 to 0.0, 3.0 to 2.0)
        // A member whose name resolves but is longer than the sidebar's 20
        // characters, and one whose name nothing knows.
        val longNamed = UUID.randomUUID()
        checkNotNull(MCTraveler.persistence).names.record(longNamed, "AVeryLongResidentName")
        region.members.add(longNamed)
        region.members.add(UUID.randomUUID())
        region.title = "A Region Title Far Too Long To Fit"
        RegionsFeature.requireService().save()
        alice.standAt(helper, 0.0, 1.0, 7.0)
        val view = SidebarView(alice)

        helper.runAfterDelay(2) { alice.standAt(helper, 1.0, 1.0, 1.0) }
        helper.runAfterDelay(4) {
            view.refresh()
            helper.assertValueEqual(view.title, heading("A Region Title Far T"), "the truncated sidebar heading")
            helper.assertValueEqual(
                view.lines,
                listOf(separator, residents, Paint.gray("AVeryLongResidentNam"), Paint.white("T13SbLong")),
                "the sidebar with a long-named and an unknown member",
            )
            alice.leave()
            helper.succeed()
        }
    }

    // ---- live updates ----

    @GameTest
    fun addingAndRemovingAMemberUpdatesEveryoneInside(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SbAddA")
        val bob = MessageCapturingPlayer.join(helper, "T13SbAddB")
        val carol = MessageCapturingPlayer.join(helper, "T13SbAddC")
        createRegion(helper, alice, 0.0 to 0.0, 3.0 to 2.0)
        bob.standAt(helper, 1.0, 1.0, 1.0)
        val aliceView = SidebarView(alice)
        val bobView = SidebarView(bob)

        helper.runAfterDelay(2) {
            alice.runCommand("rg add T13SbAddC")
            helper.assertValueEqual(
                aliceView.refresh().lines,
                listOf(separator, residents, Paint.gray("T13SbAddC"), Paint.white("T13SbAddA")),
                "the owner's sidebar after an add",
            )
            helper.assertValueEqual(
                bobView.refresh().lines,
                listOf(separator, residents, Paint.gray("T13SbAddC"), Paint.gray("T13SbAddA")),
                "a bystander's sidebar after an add",
            )

            alice.runCommand("rg remove T13SbAddC")
            helper.assertValueEqual(
                aliceView.refresh().lines,
                listOf(separator, residents, Paint.white("T13SbAddA")),
                "the owner's sidebar after a removal",
            )
            helper.assertValueEqual(
                bobView.refresh().lines,
                listOf(separator, residents, Paint.gray("T13SbAddA")),
                "a bystander's sidebar after a removal",
            )
            alice.leave()
            bob.leave()
            carol.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun renamingUpdatesTheHeadingForEveryoneInside(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SbRenA")
        val bob = MessageCapturingPlayer.join(helper, "T13SbRenB")
        createRegion(helper, alice, 0.0 to 0.0, 3.0 to 2.0)
        bob.standAt(helper, 1.0, 1.0, 1.0)
        val bobView = SidebarView(bob)

        helper.runAfterDelay(2) {
            alice.runCommand("rg rename The Renamed Place")
            helper.assertValueEqual(
                bobView.refresh().title,
                heading("The Renamed Place"),
                "a bystander's sidebar heading after a rename",
            )
            alice.leave()
            bob.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun deletingClearsTheSidebarForEveryoneInside(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SbDelA")
        val bob = MessageCapturingPlayer.join(helper, "T13SbDelB")
        createRegion(helper, alice, 0.0 to 0.0, 3.0 to 2.0)
        bob.standAt(helper, 1.0, 1.0, 1.0)
        val bobView = SidebarView(bob)

        helper.runAfterDelay(2) {
            helper.assertTrue(bobView.refresh().visible, "no sidebar before the delete")
            alice.runCommand("rg delete")
            bobView.refresh()
            helper.assertFalse(bobView.visible, "the sidebar survived the region's deletion")
            helper.assertValueEqual(bobView.lines, emptyList<Component>(), "the sidebar lines after the delete")
            alice.leave()
            bob.leave()
            helper.succeed()
        }
    }

    // ---- moving between regions ----

    @GameTest
    fun steppingIntoTheNextRegionSwapsTheSidebarCleanly(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SbAdjA")
        val bob = MessageCapturingPlayer.join(helper, "T13SbAdjB")
        createRegion(helper, alice, 0.0 to 0.0, 3.0 to 2.0)
        createRegion(helper, bob, 5.0 to 0.0, 8.0 to 2.0)
        val view = SidebarView(alice)

        helper.runAfterDelay(2) {
            helper.assertValueEqual(view.refresh().title, heading("T13SbAdjA's Place"), "the first region's heading")
            alice.standAt(helper, 6.0, 1.0, 1.0)
        }
        helper.runAfterDelay(4) {
            view.refresh()
            helper.assertTrue(view.visible, "no sidebar in the second region")
            helper.assertValueEqual(view.title, heading("T13SbAdjB's Place"), "the second region's heading")
            helper.assertValueEqual(
                view.lines,
                listOf(separator, residents, Paint.gray("T13SbAdjB")),
                "the second region's sidebar lines",
            )
            alice.leave()
            bob.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun aNoScoreboardRegionShowsNoSidebarAndHidesThePreviousOne(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SbFlagA")
        val bob = MessageCapturingPlayer.join(helper, "T13SbFlagB")
        createRegion(helper, alice, 0.0 to 0.0, 3.0 to 2.0)
        val quiet = createRegion(helper, bob, 5.0 to 0.0, 8.0 to 2.0)
        quiet.flags.add("NO_SCOREBOARD")
        RegionsFeature.requireService().save()
        val view = SidebarView(alice)

        helper.runAfterDelay(2) {
            helper.assertTrue(view.refresh().visible, "no sidebar in the ordinary region")
            alice.standAt(helper, 6.0, 1.0, 1.0) // into the NO_SCOREBOARD region
        }
        helper.runAfterDelay(4) {
            view.refresh()
            helper.assertFalse(view.visible, "a NO_SCOREBOARD region showed a sidebar")
            helper.assertValueEqual(view.lines, emptyList<Component>(), "the sidebar lines in a NO_SCOREBOARD region")
            alice.leave()
            bob.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun togglingNoScoreboardTakesTheSidebarAwayAndGivesItBack(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SbTogA")
        val admin = MessageCapturingPlayer.join(helper, "T13SbTogOp")
        createRegion(helper, alice, 0.0 to 0.0, 3.0 to 2.0)
        admin.makeAdmin()
        admin.standAt(helper, 1.0, 1.0, 1.0)
        val view = SidebarView(alice)

        helper.runAfterDelay(2) {
            helper.assertTrue(view.refresh().visible, "no sidebar before the flag was set")

            admin.runCommand("rg flag NO_SCOREBOARD")
            view.refresh()
            helper.assertFalse(view.visible, "the sidebar survived NO_SCOREBOARD being set")

            admin.runCommand("rg flag NO_SCOREBOARD")
            view.refresh()
            helper.assertTrue(view.visible, "the sidebar did not come back when NO_SCOREBOARD was cleared")
            helper.assertValueEqual(
                view.lines,
                listOf(separator, residents, Paint.white("T13SbTogA")),
                "the sidebar lines after the flag went away again",
            )
            alice.leave()
            admin.leave()
            helper.succeed()
        }
    }
}
