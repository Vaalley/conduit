package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.passport.PassportEvents
import eu.mctraveler.passport.PassportFeature
import eu.mctraveler.passport.PostcardEvent
import eu.mctraveler.passport.StampEvent
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionsFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper

class PassportGameTest {
    @GameTest(maxTicks = 80)
    fun postcardCommandRecordsPostcard(helper: GameTestHelper) {
        PassportEvents.clear()
        val player = MessageCapturingPlayer.join(helper, "PassportPostcard")
        helper.runAfterDelay(5) {
            player.runCommand("postcard hello")
            val passport = checkNotNull(MCTraveler.persistence?.passports?.get(player.uuid))
            val event = PassportEvents.poll(0).filterIsInstance<PostcardEvent>()
                .firstOrNull { it.player == player.gameProfile.name }
            helper.assertTrue(
                player.messages.any { it.string.contains("Postcard sent from") },
                "postcard reply was ${player.messages.map { it.string }}",
            )
            helper.assertTrue(passport.postcards == 1, "postcard count was ${passport.postcards}")
            helper.assertTrue(event?.caption == "hello", "postcard event was $event")
            player.leave()
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 80)
    fun postcardCommandHasCooldown(helper: GameTestHelper) {
        PassportEvents.clear()
        val player = MessageCapturingPlayer.join(helper, "PassportPostcardCooldown")
        helper.runAfterDelay(5) {
            player.runCommand("postcard hello")
            player.runCommand("postcard again")
            val passport = checkNotNull(MCTraveler.persistence?.passports?.get(player.uuid))
            helper.assertTrue(
                player.messages.any { it.string.contains("another postcard") },
                "cooldown reply was ${player.messages.map { it.string }}",
            )
            helper.assertTrue(passport.postcards == 1, "postcard count was ${passport.postcards}")
            player.leave()
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun walkingUnlocksStamp(helper: GameTestHelper) {
        PassportEvents.clear()
        val player = MessageCapturingPlayer.join(helper, "PassportStamp")
        val passport = checkNotNull(MCTraveler.persistence?.passports?.get(player.uuid))
        passport.distance.walk = 1000.0
        player.standAt(helper, 0.0, 1.0, 0.0)
        helper.runAfterDelay(2) {
            player.standAt(helper, 1.0, 1.0, 0.0)
        }
        helper.runAfterDelay(25) {
            val updated = checkNotNull(MCTraveler.persistence?.passports?.get(player.uuid))
            helper.assertTrue(updated.stamps["first_steps"] != null, "first_steps was not unlocked")
            helper.assertTrue(
                PassportEvents.poll(0).filterIsInstance<StampEvent>()
                    .any { it.player == player.gameProfile.name && it.stamp.id == "first_steps" },
                "first_steps event was not recorded",
            )
            player.leave()
            helper.succeed()
        }
    }

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
        val otherRoot = Region("Other Region", "world", 20, 20, 24, 24)
        val service = RegionsFeature.requireService()
        service.add(region, null)
        service.add(otherRoot, null)
        helper.runAfterDelay(25) {
            val passport = checkNotNull(MCTraveler.persistence?.passports?.get(player.uuid))
            val id = service.stableIdOf(region)
            val at = passport.regions[id]
            helper.assertTrue(at != null, "region was not stamped")
            service.remove(otherRoot)
            helper.runAfterDelay(25) {
                helper.assertTrue(
                    service.byStableId(id) === region &&
                        passport.regions[id] == at,
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
