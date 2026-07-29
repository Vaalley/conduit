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
        // Other gametests may field their own fake players on this shared server,
        // so assert the invariant (advertised state tracks the live roster), not
        // an absolute count.
        helper.succeedWhen {
            val players = checkNotNull(server.status).players().orElseThrow()
            val live = server.playerList.playerCount
            check(live >= 1) { "our mock player should be online" }
            helper.assertValueEqual(players.online(), live, "advertised online count")
            // Mocks join with allow-server-listings off (the vanilla client-info
            // default), so every sampled entry is the anonymous profile — the sample
            // tracks the first 12 of the live roster while honoring the opt-out.
            helper.assertValueEqual(players.sample().size, minOf(live, 12), "sample size")
            check(players.sample().all { it == MinecraftServer.ANONYMOUS_PLAYER_PROFILE }) {
                "non-listing players must appear as the anonymous entry"
            }
            server.playerList.remove(player)
        }
    }
}
