package eu.mctraveler.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.MinecraftServer

/**
 * Primary seam: the running server. Asserts the status/server-list payload the real
 * server advertises (spec User Story 1) — the Portal's exact two-line MOTD, the real
 * max-players, and a live count/sample.
 */
class MotdGameTest {

    private val expectedMotd =
        "                  play.MCTraveler.eu\n" +
            "       Celebrating 13 years of vanilla survival"

    @GameTest
    fun serverListCarriesThePortalMotd(helper: GameTestHelper) {
        val server = helper.level.server
        val status = checkNotNull(server.status) { "the server has not built a status response" }
        helper.assertValueEqual(status.description().string, expectedMotd, "server-list MOTD")
        val players = status.players().orElseThrow {
            AssertionError("the status response carries no player info")
        }
        helper.assertValueEqual(players.max(), server.playerList.maxPlayers, "advertised max players")
        helper.succeed()
    }

    @GameTest(maxTicks = 24000)
    fun playerCountAndSampleReflectOnlinePlayers(helper: GameTestHelper) {
        val server = helper.level.server
        val player = helper.makeMockServerPlayerInLevel()
        // The status response is rebuilt on a wall-clock cadence (~5 s of ticking);
        // wait for the refresh that includes the freshly-joined player.
        helper.succeedWhen {
            val players = checkNotNull(server.status).players().orElseThrow()
            helper.assertValueEqual(players.online(), 1, "advertised online count")
            // The mock joins with allow-server-listings off (the vanilla client-info
            // default), so the sample carries the anonymous entry — the sample tracks
            // the live roster while honoring the player's listing opt-out.
            helper.assertValueEqual(
                players.sample(),
                listOf(MinecraftServer.ANONYMOUS_PLAYER_PROFILE),
                "player sample",
            )
            server.playerList.remove(player)
        }
    }
}
