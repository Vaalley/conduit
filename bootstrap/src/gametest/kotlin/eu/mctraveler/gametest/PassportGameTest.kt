package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.passport.PassportFeature
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionsFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper

class PassportGameTest {
    @GameTest(maxTicks = 120)
    fun walkingAccumulatesDistance(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "PassportWalk")
        player.standAt(helper, 0.0, 1.0, 0.0)
        helper.runAfterDelay(2) {
            repeat(6) { step ->
                helper.runAfterDelay((step + 1).toLong()) {
                    player.standAt(helper, (step + 1) * 5.0, 1.0, 0.0)
                }
            }
        }
        helper.runAfterDelay(20) {
            val distance = checkNotNull(MCTraveler.persistence?.passports?.get(player.uuid)).distance.walk
            helper.assertTrue(distance in 29.0..31.0, "walked distance was $distance")
            player.leave()
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun regionVisitIsStampedOnce(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "PassportRegion")
        player.standAt(helper, 2.0, 1.0, 2.0)
        val region = Region("Passport Region", "world", 0, 0, 4, 4)
        val service = RegionsFeature.requireService()
        service.add(region, null)
        helper.runAfterDelay(25) {
            val passport = checkNotNull(MCTraveler.persistence?.passports?.get(player.uuid))
            val at = passport.regions["${service.idOf(region)}"]
            helper.assertTrue(at != null, "region was not stamped")
            helper.runAfterDelay(25) {
                helper.assertTrue(
                    passport.regions["${service.idOf(region)}"] == at,
                    "region visit timestamp changed",
                )
                service.remove(region)
                player.leave()
                helper.succeed()
            }
        }
    }

    @GameTest(maxTicks = 80)
    fun passportCommandPrintsSummary(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "PassportCommand")
        helper.runAfterDelay(5) {
            player.runCommand("passport")
            helper.assertTrue(
                player.messages.any { it.string.contains("Passport of PassportCommand") },
                "passport command output was ${player.messages.map { it.string }}",
            )
            player.leave()
            helper.succeed()
        }
    }
}
