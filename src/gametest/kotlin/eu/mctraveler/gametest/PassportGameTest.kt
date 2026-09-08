package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.passport.PassportEvents
import eu.mctraveler.passport.PassportFeature
import eu.mctraveler.passport.PostcardEvent
import eu.mctraveler.passport.StampEvent
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionsFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.core.component.DataComponents
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket

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
        val origin = helper.absolutePos(BlockPos.ZERO)
        val region = Region("Passport Region", "world", origin.x, origin.z, origin.x + 4, origin.z + 4)
        val otherRoot = Region("Other Region", "world", origin.x + 20, origin.z + 20, origin.x + 24, origin.z + 24)
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
            player.runCommand("passport text")
            helper.assertTrue(
                player.messages.any { it.string.contains("Passport of PassportCommand") },
                "passport command output was ${player.messages.map { it.string }}",
            )
            player.leave()
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 80)
    fun passportCommandOpensReadOnlyMenu(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "PassportMenu")
        helper.runAfterDelay(5) {
            player.runCommand("passport")
            val menu = checkNotNull(player.containerMenu as? eu.mctraveler.passport.PassportMenu.PassportChestMenu) {
                "passport did not open a chest menu"
            }
            val opened = checkNotNull(PacketCapture.drainOf<ClientboundOpenScreenPacket>(player).lastOrNull()) {
                "passport did not send an open-screen packet"
            }
            helper.assertValueEqual(opened.title.string, "Passport of PassportMenu", "passport menu title")
            val lore = menu.getSlot(4).item.get(DataComponents.LORE)?.lines().orEmpty().map { it.string }
            helper.assertTrue(lore.any { it.contains("Traveler since") }, "head lore was $lore")
            menu.clicked(22, 0, ContainerInput.PICKUP, player)
            helper.runAfterDelay(1) {
                helper.assertTrue(
                    player.containerMenu is eu.mctraveler.passport.PassportMenu.PassportChestMenu,
                    "stamps click did not keep a passport menu open",
                )
                helper.assertTrue(
                    (player.containerMenu as eu.mctraveler.passport.PassportMenu.PassportChestMenu).getSlot(26).item.`is`(net.minecraft.world.item.Items.ARROW),
                    "stamps page did not show the back arrow",
                )
                player.leave()
                helper.succeed()
            }
        }
    }

    @GameTest(maxTicks = 80)
    fun passportUnknownPlayerReportsError(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "PassportUnknown")
        helper.runAfterDelay(5) {
            player.runCommand("passport NobodyHere")
            helper.assertTrue(
                player.messages.any { it.string.contains("Unknown player NobodyHere") },
                "unknown-player reply was ${player.messages.map { it.string }}",
            )
            player.leave()
            helper.succeed()
        }
    }
}
