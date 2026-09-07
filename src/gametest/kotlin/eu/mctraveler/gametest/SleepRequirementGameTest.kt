package eu.mctraveler.gametest

import eu.mctraveler.away.AwayFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.players.SleepStatus

/**
 * Issue #52: a player marked away (`/away` or five idle minutes) is dropped from the
 * denominator the server uses to decide whether enough players are asleep to skip the
 * night, so one AFK player can never hold the whole server awake.
 *
 * Asserted at `SleepStatus`, the maths itself — `SleepRequirementMixin` filters the
 * player list every `ServerLevel` hands in.
 */
class SleepRequirementGameTest {

    @GameTest(maxTicks = 100)
    fun anAwayPlayerIsNotCountedTowardTheSleepRequirement(helper: GameTestHelper) {
        val awake = AwayTestPlayer.join(helper, "SleepAwake")
        val afk = AwayTestPlayer.join(helper, "SleepAfk")
        val other = AwayTestPlayer.join(helper, "SleepOther")

        helper.runAfterDelay(2) { afk.runCommand("away") }

        helper.runAfterDelay(6) {
            check(AwayFeature.isAway(afk)) { "the /away player is not marked away" }
            check(!AwayFeature.isAway(awake)) { "an active player was marked away" }

            // Two awake players, nobody sleeping, 100% required: it takes both.
            val awakePair = SleepStatus()
            awakePair.update(listOf(awake, other))
            helper.assertValueEqual(awakePair.sleepersNeeded(100), 2, "two awake players both count")

            // Swap one for the away player: only the awake one is in the denominator now.
            val withAway = SleepStatus()
            withAway.update(listOf(awake, afk))
            helper.assertValueEqual(withAway.sleepersNeeded(100), 1, "the away player drops out of the denominator")

            awake.leave()
            afk.leave()
            other.leave()
            helper.succeed()
        }
    }
}
