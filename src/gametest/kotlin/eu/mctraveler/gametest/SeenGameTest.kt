package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/**
 * `/seen <player>`: a public summary of when a player was last on, when they
 * first joined, where they last joined from and what they own. A vanished
 * admin reads as offline to everyone who is not an admin.
 */
class SeenGameTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @GameTest(maxTicks = 100)
    fun anyoneSeesAnOfflinePlayersSummary(helper: GameTestHelper) {
        val server = helper.level.server
        val viewer = TestPlayer.join(server, "SeenViewerA")
        val target = TestPlayer.join(server, "SeenTargetA")
        val uuid = target.player.uuid
        target.disconnect()

        helper.runAfterDelay(3) {
            val players = MCTraveler.persistence!!.players
            // 2 days 3 hours 4 minutes 30 seconds ago: the 30 seconds round up to a whole minute.
            players.setLogoutMetadata(uuid, System.currentTimeMillis() - (2 * day + 3 * hour + 4 * minute + 30_000), "overworld", 0.0, 0.0, 0.0)
            players.setBalance(uuid, 1234)
            viewer.runCommand("seen SeenTargetA")

            val shown = viewer.systemMessages().map { it.string }
            helper.assertTrue(
                "SeenTargetA was last seen 2 days, 3 hours, 5 minutes ago." in shown,
                "headline missing from $shown",
            )
            helper.assertTrue(shown.any { it.startsWith("First joined on ") && !it.endsWith("Unknown") }, "first join line missing from $shown")
            helper.assertTrue(shown.any { it.startsWith("Last joined from ") }, "location line missing from $shown")
            helper.assertTrue("Balance $12.34" in shown, "balance line missing from $shown")
            viewer.disconnect()
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun unknownAndNeverJoinedPlayersAreRefused(helper: GameTestHelper) {
        val viewer = TestPlayer.join(helper.level.server, "SeenViewerB")
        helper.runAfterDelay(2) {
            viewer.runCommand("seen NeverJoinedSeenPlayer")
            helper.assertTrue(viewer.systemMessages().any { it.string.contains("Unknown player") }, "unknown output was ${viewer.systemMessages().map { it.string }}")
            viewer.disconnect()
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun anOnlinePlayerIsShownAsOnline(helper: GameTestHelper) {
        val server = helper.level.server
        val viewer = TestPlayer.join(server, "SeenViewerC")
        val target = TestPlayer.join(server, "SeenTargetC")
        helper.runAfterDelay(2) {
            viewer.runCommand("seen SeenTargetC")
            val shown = viewer.systemMessages().map { it.string }
            helper.assertTrue("SeenTargetC is online right now." in shown, "online headline missing from $shown")
            viewer.disconnect()
            target.disconnect()
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun aVanishedAdminReadsAsOfflineToEveryoneButAdmins(helper: GameTestHelper) {
        val server = helper.level.server
        val stranger = TestPlayer.join(server, "SeenStrangerD")
        val adminViewer = TestPlayer.join(server, "SeenAdminViewerD").also { server.playerList.op(it.player.nameAndId()) }
        val vanished = TestPlayer.join(server, "SeenVanishedD").also { server.playerList.op(it.player.nameAndId()) }
        vanished.runCommand("vanish")

        helper.runAfterDelay(3) {
            MCTraveler.persistence!!.players.setLogoutMetadata(
                vanished.player.uuid, System.currentTimeMillis() - 2 * hour, "overworld", 0.0, 0.0, 0.0,
            )
            stranger.runCommand("seen SeenVanishedD")
            adminViewer.runCommand("seen SeenVanishedD")

            val strangerSees = stranger.systemMessages().map { it.string }
            val adminSees = adminViewer.systemMessages().map { it.string }
            helper.assertTrue("SeenVanishedD was last seen 2 hours ago." in strangerSees, "stranger saw $strangerSees")
            helper.assertTrue("SeenVanishedD is online right now." in adminSees, "admin saw $adminSees")
            stranger.disconnect()
            adminViewer.disconnect()
            vanished.disconnect()
            helper.succeed()
        }
    }
}
