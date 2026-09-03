package eu.mctraveler.gametest

import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EndPortalFrameBlock
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3

/**
 * What a region actually stops a stranger doing, at the running-server seam
 * (spec stories 34, 38 and 39): each action either happens or does not, and
 * every refusal carries the Portal's one message (RegionFeature.ts; inventory
 * §2.8).
 *
 * Every test builds its region through the real commands and keeps every
 * coordinate within a few blocks of its own structure — the gametest batch
 * lays structures out roughly 15 blocks apart, so anything further can stray
 * into a neighbouring test's region.
 */
class RegionProtectionGameTest {

    // ---- digging ----

    @GameTest
    fun aNonMemberCannotDig(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14DigA")
        val bob = MessageCapturingPlayer.join(helper, "T14DigB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.STONE)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.messages.clear()
        bob.chatMessages.clear()
        bob.actionBarMessages.clear()

        helper.assertFalse(bob.digs(helper), "a non-member broke a block in someone else's region")
        helper.assertBlockPresent(Blocks.STONE, STONE_AT)
        val expectedRefusal = protectedBy("T14DigA's Place")
        helper.assertValueEqual(bob.messages.last(), expectedRefusal, "the dig refusal")
        helper.assertValueEqual(
            bob.actionBarMessages.single(),
            expectedRefusal,
            "the refusal shown in the action bar",
        )
        helper.assertFalse(
            bob.chatMessages.any { it == expectedRefusal },
            "the protection refusal was added to chat",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentDigsFreely(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14MineA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.STONE)
        alice.standAt(helper, 2.0, 2.0, 1.0)

        helper.assertTrue(alice.digs(helper), "a resident could not break a block in their own region")
        helper.assertBlockNotPresent(Blocks.STONE, STONE_AT)
        helper.assertFalse(alice.wasRefusedBy("T14MineA's Place"), "a resident was refused in their own region")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aPublicRegionLetsAnyoneDig(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14PubA")
        val bob = MessageCapturingPlayer.join(helper, "T14PubB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag PUBLIC")
        helper.setBlock(STONE_AT, Blocks.STONE)
        bob.standAt(helper, 2.0, 2.0, 1.0)

        helper.assertTrue(bob.digs(helper), "a public region refused a stranger")
        helper.assertFalse(bob.wasRefusedBy("T14PubA's Place"), "a public region still refused a stranger")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun anAdminIsStillAStrangerToProtection(helper: GameTestHelper) {
        // Operator status bypasses region management, never protection itself.
        val alice = MessageCapturingPlayer.join(helper, "T14OpA")
        val admin = MessageCapturingPlayer.join(helper, "T14OpAdmin")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        admin.makeAdmin()
        helper.setBlock(STONE_AT, Blocks.STONE)
        admin.standAt(helper, 2.0, 2.0, 1.0)

        helper.assertFalse(admin.digs(helper), "an admin dug through region protection")
        helper.assertValueEqual(
            admin.messages.last(),
            protectedBy("T14OpA's Place"),
            "the admin's dig refusal",
        )
        alice.leave()
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun theDigIsRefusedTheMomentItStarts(helper: GameTestHelper) {
        // An instant break is the whole dig, so the refusal has to land on the
        // very first packet — not only on the break that would have followed.
        val alice = MessageCapturingPlayer.join(helper, "T14StartA")
        val bob = MessageCapturingPlayer.join(helper, "T14StartB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.STONE)
        bob.setGameMode(GameType.CREATIVE)
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.startsDigging(helper)
        helper.assertBlockPresent(Blocks.STONE, STONE_AT)
        helper.assertValueEqual(
            bob.messages.last(),
            protectedBy("T14StartA's Place"),
            "the dig-start refusal",
        )

        alice.setGameMode(GameType.CREATIVE)
        alice.standAt(helper, 2.0, 2.0, 1.0)
        alice.startsDigging(helper)
        helper.assertBlockNotPresent(Blocks.STONE, STONE_AT)
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest(maxTicks = 100)
    fun aDigRefusalIsThrottledButNeverAllowed(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14ThrottleA")
        val bob = MessageCapturingPlayer.join(helper, "T14ThrottleB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.STONE)
        bob.setGameMode(GameType.CREATIVE)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.messages.clear()

        val expectedRefusal = protectedBy("T14ThrottleA's Place")
        bob.startsDigging(helper)
        helper.assertBlockPresent(Blocks.STONE, STONE_AT)
        val firstRefusals = bob.messages.filter { it == expectedRefusal }
        helper.assertValueEqual(firstRefusals.size, 1, "the first refusal was not immediate")
        helper.assertValueEqual(
            firstRefusals.first(),
            expectedRefusal,
            "the first refusal",
        )

        bob.startsDigging(helper)
        helper.assertBlockPresent(Blocks.STONE, STONE_AT)
        val immediateRefusals = bob.messages.filter { it == expectedRefusal }
        helper.assertValueEqual(immediateRefusals.size, 1, "a rapid repeat emitted another refusal")

        helper.runAfterDelay(20) {
            bob.startsDigging(helper)
            helper.assertBlockPresent(Blocks.STONE, STONE_AT)
            val expiredRefusals = bob.messages.filter { it == expectedRefusal }
            helper.assertValueEqual(expiredRefusals.size, 2, "the refusal did not reappear after 20 ticks")
            helper.assertValueEqual(
                expiredRefusals.last(),
                expectedRefusal,
                "the refusal after the cooldown",
            )
            alice.leave()
            bob.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun nonBlockingItemUsesAreNotRefusedInsideForeignLand(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14SafeUseA")
        val bob = MessageCapturingPlayer.join(helper, "T14SafeUseB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(FLOOR_AT, Blocks.STONE)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.messages.clear()

        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.BREAD))
        helper.assertTrue(
            bob.usesHeldItemOn(helper, FLOOR_AT),
            "food was refused while used on a block inside foreign land",
        )
        helper.assertFalse(
            bob.wasRefusedBy("T14SafeUseA's Place"),
            "food-on-block emitted a protection refusal",
        )
        helper.assertTrue(bob.messages.isEmpty(), "an exempt food emitted an unrelated message")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun blockChangingUsesOfSafeAirItemsStayProtected(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14SafeBlockA")
        val bob = MessageCapturingPlayer.join(helper, "T14SafeBlockB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.DIRT)
        helper.setBlock(OUTER_AT, Blocks.END_PORTAL_FRAME)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.messages.clear()

        bob.setItemInHand(InteractionHand.MAIN_HAND, Items.POTION.defaultInstance)
        helper.assertFalse(
            bob.usesHeldItemOn(helper, STONE_AT),
            "a water potion converted protected dirt",
        )
        helper.assertBlockPresent(Blocks.DIRT, STONE_AT)

        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.ENDER_EYE))
        helper.assertFalse(
            bob.usesHeldItemOn(helper, OUTER_AT),
            "an ender eye filled a protected portal frame",
        )
        helper.assertBlockProperty(OUTER_AT, EndPortalFrameBlock.HAS_EYE, false)
        helper.assertTrue(bob.wasRefusedBy("T14SafeBlockA's Place"), "the block-changing uses emitted no refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun diggingOutsideEveryRegionIsUntouched(helper: GameTestHelper) {
        val digger = MessageCapturingPlayer.join(helper, "T14Free")
        helper.setBlock(STONE_AT, Blocks.STONE)
        digger.standAt(helper, 2.0, 2.0, 1.0)

        helper.assertTrue(digger.digs(helper), "unprotected ground refused a dig")
        digger.leave()
        helper.succeed()
    }

    // ---- building ----

    @GameTest
    fun aNonMemberCannotPlaceABlock(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14PlaceA")
        val bob = MessageCapturingPlayer.join(helper, "T14PlaceB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(FLOOR_AT, Blocks.STONE)
        bob.standAt(helper, 1.0, 2.0, 1.0)

        bob.placesOn(helper, FLOOR_AT)
        helper.assertBlockNotPresent(Blocks.STONE, STONE_AT)
        helper.assertValueEqual(bob.messages.last(), protectedBy("T14PlaceA's Place"), "the place refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentBuildsFreely(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14BuildA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(FLOOR_AT, Blocks.STONE)
        alice.standAt(helper, 1.0, 2.0, 1.0)

        alice.placesOn(helper, FLOOR_AT)
        helper.assertBlockPresent(Blocks.STONE, STONE_AT)
        helper.assertFalse(alice.wasRefusedBy("T14BuildA's Place"), "a resident was refused their own build")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aSubRegionAnswersForItsOwnGround(helper: GameTestHelper) {
        // The deepest region wins: inside the sub-region only its members may
        // build, and the parent's owner is a stranger there.
        val alice = MessageCapturingPlayer.join(helper, "T14SubA")
        val bob = MessageCapturingPlayer.join(helper, "T14SubB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val sub = createRegion(helper, alice, 1.0 to 1.0, 3.0 to 4.0)
        alice.runCommand("rg rename Inner Yard")
        sub.members.remove(alice.uuid)
        sub.members.add(bob.uuid)
        RegionsFeature.requireService().save()

        helper.setBlock(STONE_AT, Blocks.STONE) // inside the sub-region
        helper.setBlock(OUTER_AT, Blocks.STONE) // inside the parent only

        bob.standAt(helper, 2.0, 2.0, 1.0)
        helper.assertTrue(bob.digs(helper), "the sub-region's own member could not dig in it")

        alice.standAt(helper, 2.0, 2.0, 1.0)
        helper.setBlock(STONE_AT, Blocks.STONE)
        helper.assertFalse(alice.digs(helper), "the parent's owner dug inside the sub-region")
        helper.assertValueEqual(alice.messages.last(), protectedBy("Inner Yard"), "the sub-region refusal")

        alice.standAt(helper, 0.0, 2.0, 1.0)
        helper.assertTrue(alice.digs(helper, OUTER_AT), "the parent's owner could not dig in their own region")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    // ---- sign editing ----

    @GameTest
    fun aNonMemberCannotEditASign(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14SignA")
        val bob = MessageCapturingPlayer.join(helper, "T14SignB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val sign = helper.placeSignFor(bob)
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.writesOnSign(helper, "Mine", "now")
        // The sign text takes vanilla's text-filter round trip through the
        // server's task queue before it lands.
        helper.runAfterDelay(2) {
            helper.assertValueEqual(signLine(sign), "", "the sign a stranger wrote on")
            helper.assertTrue(bob.wasRefusedBy("T14SignA's Place"), "no sign-edit refusal")
            alice.leave()
            bob.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun aResidentEditsASign(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14SignOk")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val sign = helper.placeSignFor(alice)
        alice.standAt(helper, 2.0, 2.0, 1.0)

        alice.writesOnSign(helper, "Home", "sweet")
        helper.runAfterDelay(2) {
            helper.assertValueEqual(signLine(sign), "Home", "the sign a resident wrote on")
            alice.leave()
            helper.succeed()
        }
    }

    // ---- containers ----

    @GameTest
    fun aNonMemberCannotTakeFromAChest(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14BoxA")
        val bob = MessageCapturingPlayer.join(helper, "T14BoxB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.stockedChest()
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.opensChest(helper)
        bob.clicksFirstSlot()
        helper.assertTrue(bob.containerMenu.carried.isEmpty, "a stranger took from a protected chest")
        helper.assertValueEqual(bob.messages.last(), protectedBy("T14BoxA's Place"), "the container refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentTakesFromTheirOwnChest(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14BoxOk")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.stockedChest()
        alice.standAt(helper, 2.0, 2.0, 1.0)

        alice.opensChest(helper)
        alice.clicksFirstSlot()
        helper.assertFalse(alice.containerMenu.carried.isEmpty, "a resident could not take from their chest")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentOpensTheirChestByRightClicking(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14BoxClick")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.stockedChest()
        alice.standAt(helper, 2.0, 2.0, 1.0)

        helper.assertTrue(alice.usesHeldItemOn(helper, CHEST_AT), "a resident's chest right-click was refused")
        helper.assertTrue(alice.containerMenu is ChestMenu, "a resident's chest did not open")
        alice.clicksFirstSlot()
        helper.assertFalse(alice.containerMenu.carried.isEmpty, "a resident could not take from their right-clicked chest")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberMayUseTheirPersonalEnderChestInForeignLand(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14EnderA")
        val bob = MessageCapturingPlayer.join(helper, "T14EnderB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        bob.standAt(helper, 2.0, 1.0, 2.0)
        bob.enderChestInventory.setItem(0, ItemStack(Items.DIAMOND))
        bob.messages.clear()

        bob.opensEnderChest()
        bob.clicksFirstSlot()

        helper.assertFalse(
            bob.containerMenu.carried.isEmpty,
            "a foreign-region player could not take from their personal ender chest",
        )
        helper.assertTrue(bob.messages.isEmpty(), "the personal ender chest emitted a protection refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun publicContainersOpenTheChestToStrangers(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14CtnA")
        val bob = MessageCapturingPlayer.join(helper, "T14CtnB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag ENABLE_PUBLIC_CONTAINERS")
        helper.stockedChest()
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.opensChest(helper)
        bob.clicksFirstSlot()
        helper.assertFalse(bob.containerMenu.carried.isEmpty, "ENABLE_PUBLIC_CONTAINERS kept the chest shut")
        helper.assertFalse(bob.wasRefusedBy("T14CtnA's Place"), "a public container still refused a stranger")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aPublicRegionOpensItsChestsToo(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14PubBoxA")
        val bob = MessageCapturingPlayer.join(helper, "T14PubBoxB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag PUBLIC")
        helper.stockedChest()
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.opensChest(helper)
        bob.clicksFirstSlot()
        helper.assertFalse(bob.containerMenu.carried.isEmpty, "a public region kept its chest shut")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun theChestIsJudgedWhereItWasOpened(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14OpenA")
        val bob = MessageCapturingPlayer.join(helper, "T14OpenB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.stockedChest()
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.opensChest(helper)

        bob.standAt(helper, 6.0, 2.0, 6.0) // out of the region, chest still open
        bob.clicksFirstSlot()
        helper.assertTrue(bob.containerMenu.carried.isEmpty, "stepping outside unlocked an open chest")
        helper.assertValueEqual(
            bob.messages.last(),
            protectedBy("T14OpenA's Place"),
            "the refusal from the region the chest was opened in",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    // ---- item use ----

    @GameTest
    fun aNonMemberMayUseAHarmlessItemInAForeignRegion(helper: GameTestHelper) {
        // Issue #40: an item used in the air touches nothing, so it says
        // nothing — the region only refuses a use that would change it.
        val alice = MessageCapturingPlayer.join(helper, "T14UseA")
        val bob = MessageCapturingPlayer.join(helper, "T14UseB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        bob.standAt(helper, 2.0, 1.0, 2.0)
        bob.messages.clear()

        for (item in listOf(Items.SPYGLASS, Items.GOAT_HORN, Items.MAP, Items.STICK)) {
            bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(item))
            bob.usesHeldItem()
        }
        helper.assertFalse(bob.wasRefusedBy("T14UseA's Place"), "a harmless item earned a refusal")
        helper.assertTrue(bob.messages.isEmpty(), "a harmless item-use emitted a message")

        // A bucket used at the feet still is — it would place a fluid.
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.WATER_BUCKET))
        helper.assertFalse(bob.usesHeldItem(), "a stranger poured a bucket in a region")
        helper.assertTrue(bob.wasRefusedBy("T14UseA's Place"), "the bucket use emitted no refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun anEmptyHandIsNotAnItemUse(helper: GameTestHelper) {
        // The Portal only ever saw this hook fire with something in hand.
        val alice = MessageCapturingPlayer.join(helper, "T14EmptyA")
        val bob = MessageCapturingPlayer.join(helper, "T14EmptyB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        bob.standAt(helper, 2.0, 1.0, 2.0)

        bob.usesHeldItem()
        helper.assertFalse(bob.wasRefusedBy("T14EmptyA's Place"), "an empty hand was refused as an item use")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aStrangerCannotPotOrUnpotFlowersInARegion(helper: GameTestHelper) {
        // Issue #40: a flower pot changes what is in a region, so a non-member
        // is refused at it — potting and un-potting alike.
        val alice = MessageCapturingPlayer.join(helper, "T14PotA")
        val bob = MessageCapturingPlayer.join(helper, "T14PotB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(FLOOR_AT, Blocks.STONE)
        helper.setBlock(STONE_AT, Blocks.POTTED_POPPY)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.messages.clear()

        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        helper.assertFalse(bob.usesHeldItemOn(helper, STONE_AT), "a stranger took a flower from a region pot")
        helper.assertBlockPresent(Blocks.POTTED_POPPY, STONE_AT)
        helper.assertTrue(bob.wasRefusedBy("T14PotA's Place"), "un-potting emitted no refusal")

        helper.setBlock(STONE_AT, Blocks.FLOWER_POT)
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.POPPY))
        helper.assertFalse(bob.usesHeldItemOn(helper, STONE_AT), "a stranger potted a flower in a region")
        helper.assertBlockPresent(Blocks.FLOWER_POT, STONE_AT)
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    // ---- issue #49: buckets reach past the player's feet ----

    @GameTest
    fun aNonMemberCannotPourWaterIntoARegionFromOutside(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T49PourA")
        val bob = MessageCapturingPlayer.join(helper, "T49PourB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(BlockPos(3, 3, 2), Blocks.STONE) // aimed at, inside the region
        bob.standAt(helper, 5.5, 2.0, 2.0) // outside, reaching in over the border
        bob.face(Direction.WEST)
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.WATER_BUCKET))
        bob.messages.clear()

        bob.usesHeldItem()
        helper.assertBlockNotPresent(Blocks.WATER, BlockPos(4, 3, 2))
        helper.assertTrue(bob.mainHandItem.`is`(Items.WATER_BUCKET), "the water bucket was spent")
        helper.assertTrue(bob.wasRefusedBy("T49PourA's Place"), "pouring water into the region emitted no refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentPoursWaterIntoTheirRegionFromOutside(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T49OwnPour")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(BlockPos(3, 3, 2), Blocks.STONE)
        alice.standAt(helper, 5.5, 2.0, 2.0)
        alice.face(Direction.WEST)
        alice.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.WATER_BUCKET))

        alice.usesHeldItem()
        helper.assertBlockPresent(Blocks.WATER, BlockPos(4, 3, 2))
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberCannotBailWaterOutOfARegionFromOutside(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T49BailA")
        val bob = MessageCapturingPlayer.join(helper, "T49BailB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(BlockPos(4, 3, 2), Blocks.WATER)
        bob.standAt(helper, 6.0, 2.0, 2.0)
        bob.face(Direction.WEST)
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.BUCKET))
        bob.messages.clear()

        bob.usesHeldItem()
        helper.assertBlockPresent(Blocks.WATER, BlockPos(4, 3, 2))
        helper.assertTrue(bob.mainHandItem.`is`(Items.BUCKET), "the bucket filled from the region")
        helper.assertTrue(bob.wasRefusedBy("T49BailA's Place"), "bailing water from the region emitted no refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest(maxTicks = 120)
    fun waterDoesNotFlowIntoARegionFromOutside(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T49FlowA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        // A one-wide trench along x at z=2, floored and walled, from inside the
        // region out past its eastern border.
        for (x in 0..7) {
            helper.setBlock(BlockPos(x, 1, 2), Blocks.STONE)
            helper.setBlock(BlockPos(x, 2, 1), Blocks.STONE)
            helper.setBlock(BlockPos(x, 2, 3), Blocks.STONE)
        }
        helper.setBlock(BlockPos(6, 2, 2), Blocks.WATER) // source outside the region

        helper.runAfterDelay(80) {
            helper.assertBlockPresent(Blocks.WATER, BlockPos(5, 2, 2)) // reached the border from outside
            helper.assertBlockNotPresent(Blocks.WATER, BlockPos(4, 2, 2)) // and stopped at it
            helper.assertBlockNotPresent(Blocks.WATER, BlockPos(2, 2, 2))
            alice.leave()
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 120)
    fun waterFlowsFreelyWithinARegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T49FlowB")
        createRegion(helper, alice, 0.0 to 0.0, 6.0 to 6.0)
        for (x in 0..5) {
            helper.setBlock(BlockPos(x, 1, 2), Blocks.STONE)
            helper.setBlock(BlockPos(x, 2, 1), Blocks.STONE)
            helper.setBlock(BlockPos(x, 2, 3), Blocks.STONE)
        }
        helper.setBlock(BlockPos(0, 2, 2), Blocks.WATER) // source inside the region

        helper.runAfterDelay(80) {
            helper.assertBlockPresent(Blocks.WATER, BlockPos(4, 2, 2)) // flowed on within the region
            alice.leave()
            helper.succeed()
        }
    }

    // ---- issue #40: interaction classes ----

    @GameTest
    fun aNonMemberWorksAtAWorkstationWithoutAWord(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40WorkA")
        val bob = MessageCapturingPlayer.join(helper, "T40WorkB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.CRAFTING_TABLE)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.messages.clear()

        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        helper.assertTrue(bob.usesHeldItemOn(helper, STONE_AT), "a non-member could not open a crafting table")

        // Holding an unrelated item used to earn a refusal; now it does not.
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIAMOND_PICKAXE))
        helper.assertTrue(bob.usesHeldItemOn(helper, STONE_AT), "holding a tool blocked the crafting table")
        helper.assertTrue(bob.messages.isEmpty(), "opening a workstation emitted a message")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberOpensAFurnaceToLookButCannotChangeIt(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40FurnA")
        val bob = MessageCapturingPlayer.join(helper, "T40FurnB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.FURNACE)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.messages.clear()

        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.COAL))
        helper.assertTrue(bob.usesHeldItemOn(helper, STONE_AT), "a non-member could not open a furnace to look")
        helper.assertTrue(bob.messages.isEmpty(), "opening a furnace to view emitted a message")

        bob.clicksFirstSlot()
        helper.assertTrue(bob.wasRefusedBy("T40FurnA's Place"), "a furnace slot click was not refused")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberIsRefusedAtARegionChangingBlock(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40ChgA")
        val bob = MessageCapturingPlayer.join(helper, "T40ChgB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        bob.standAt(helper, 2.0, 2.0, 1.0)

        // Every one of these changes what is in the region, so the block's own
        // right-click is cancelled for a non-member. (The refusal message is
        // throttled per region, so only the first attempt in the batch carries
        // it — every attempt is still stopped.)
        for (block in listOf(Blocks.NOTE_BLOCK, Blocks.ANVIL, Blocks.JUKEBOX, Blocks.DECORATED_POT)) {
            helper.setBlock(STONE_AT, block)
            bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
            helper.assertFalse(bob.usesHeldItemOn(helper, STONE_AT), "a non-member interacted with $block")
        }
        helper.assertTrue(bob.wasRefusedBy("T40ChgA's Place"), "no refusal for a region-changing block")

        // A composter fill is an item-on-block, and refused with it too.
        helper.setBlock(STONE_AT, Blocks.COMPOSTER)
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.BONE_MEAL))
        helper.assertFalse(bob.usesHeldItemOn(helper, STONE_AT), "a non-member filled a region composter")
        helper.assertBlockProperty(STONE_AT, net.minecraft.world.level.block.ComposterBlock.LEVEL, 0)
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberMayCookOnACampfireButNotDouseIt(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40FireA")
        val bob = MessageCapturingPlayer.join(helper, "T40FireB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.CAMPFIRE)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.messages.clear()

        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.PORKCHOP))
        helper.assertTrue(bob.usesHeldItemOn(helper, STONE_AT), "a non-member could not set food on a campfire")
        helper.assertTrue(bob.messages.isEmpty(), "cooking on a campfire emitted a message")

        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIAMOND_SHOVEL))
        helper.assertFalse(bob.usesHeldItemOn(helper, STONE_AT), "a non-member doused a region campfire")
        helper.assertBlockProperty(STONE_AT, net.minecraft.world.level.block.CampfireBlock.LIT, true)
        helper.assertTrue(bob.wasRefusedBy("T40FireA's Place"), "dousing a campfire emitted no refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun boneMealIsInertForANonMemberAndFineForAResident(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40BoneA")
        val bob = MessageCapturingPlayer.join(helper, "T40BoneB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(FLOOR_AT, Blocks.GRASS_BLOCK)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.messages.clear()

        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.BONE_MEAL))
        helper.assertFalse(bob.usesHeldItemOn(helper, FLOOR_AT), "a non-member's bone meal worked in a region")
        helper.assertTrue(bob.wasRefusedBy("T40BoneA's Place"), "bone meal emitted no refusal")

        alice.standAt(helper, 2.0, 2.0, 1.0)
        alice.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.BONE_MEAL))
        alice.messages.clear()
        alice.usesHeldItemOn(helper, FLOOR_AT)
        helper.assertFalse(alice.wasRefusedBy("T40BoneA's Place"), "a resident's bone meal was refused")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberFullyUsesAWorkstation(helper: GameTestHelper) {
        // A grindstone (loom, stonecutter, …) holds the player's own items, so
        // a non-member may work it — the container-session protection sits out.
        val alice = MessageCapturingPlayer.join(helper, "T40StnA")
        val bob = MessageCapturingPlayer.join(helper, "T40StnB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.GRINDSTONE)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        bob.getInventory().add(ItemStack(Items.DIAMOND_SWORD))
        bob.messages.clear()

        helper.assertTrue(bob.usesHeldItemOn(helper, STONE_AT), "a non-member could not open a grindstone")
        helper.assertTrue(bob.containerMenu is net.minecraft.world.inventory.GrindstoneMenu, "the grindstone did not open")

        // Move the sword from the inventory into the grindstone's first slot.
        bob.containerMenu.clicked(bob.containerMenu.slots.indexOfFirst { it.hasItem() }, 0, ContainerInput.PICKUP, bob)
        bob.containerMenu.clicked(0, 0, ContainerInput.PICKUP, bob)
        helper.assertTrue(bob.containerMenu.getSlot(0).hasItem(), "a non-member's grindstone click was refused")
        helper.assertFalse(bob.wasRefusedBy("T40StnA's Place"), "using a grindstone earned a refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aContainerIsJudgedByItsBlockNotTheOpenersFeet(helper: GameTestHelper) {
        // Reaching a chest from just outside the region does not unlock it —
        // the container is judged by the block it is, not the opener's feet.
        val alice = MessageCapturingPlayer.join(helper, "T40OutA")
        val bob = MessageCapturingPlayer.join(helper, "T40OutB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val chestAt = BlockPos(4, 2, 2)
        helper.setBlock(chestAt, Blocks.CHEST)
        (helper.level.getBlockEntity(helper.absolutePos(chestAt)) as ChestBlockEntity)
            .setItem(0, ItemStack(Items.DIAMOND))
        bob.standAt(helper, 5.3, 2.0, 2.0) // outside the region, reaching in
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        bob.messages.clear()

        helper.assertTrue(bob.usesHeldItemOn(helper, chestAt), "a non-member could not reach the chest")
        bob.clicksFirstSlot()
        helper.assertTrue(
            bob.containerMenu.carried.isEmpty,
            "a chest reached from outside the region gave up its contents",
        )
        helper.assertTrue(bob.wasRefusedBy("T40OutA's Place"), "no refusal for a chest opened from outside")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberSeesRegionProtectedAsTheContainerTitle(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40TtlA")
        val bob = MessageCapturingPlayer.join(helper, "T40TtlB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val chestAt = BlockPos(4, 2, 2)
        helper.setBlock(chestAt, Blocks.CHEST)

        bob.standAt(helper, 5.3, 2.0, 2.0)
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        PacketCapture.drain(bob)
        bob.usesHeldItemOn(helper, chestAt)
        val bobScreen = PacketCapture.drainOf<ClientboundOpenScreenPacket>(bob).lastOrNull()
        helper.assertTrue(bobScreen != null, "no screen opened for the non-member")
        helper.assertValueEqual(bobScreen!!.title.string, "Region protected", "the container title was not rewritten")

        alice.standAt(helper, 5.3, 2.0, 2.0)
        PacketCapture.drain(alice)
        alice.usesHeldItemOn(helper, chestAt)
        val aliceScreen = PacketCapture.drainOf<ClientboundOpenScreenPacket>(alice).lastOrNull()
        helper.assertFalse(
            aliceScreen?.title?.string == "Region protected",
            "a resident saw the protected title",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberCannotOpenACrafter(helper: GameTestHelper) {
        // A crafter is not a look-inside container — its ingredients are the
        // region's and its slot layout is part of the build, so a non-member
        // does not open it at all.
        val alice = MessageCapturingPlayer.join(helper, "T40CrfA")
        val bob = MessageCapturingPlayer.join(helper, "T40CrfB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.CRAFTER)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)

        helper.assertFalse(bob.usesHeldItemOn(helper, STONE_AT), "a non-member opened a region's crafter")
        helper.assertFalse(
            bob.containerMenu is net.minecraft.world.inventory.CrafterMenu,
            "the crafter GUI opened for a non-member",
        )
        helper.assertTrue(bob.wasRefusedBy("T40CrfA's Place"), "opening a crafter emitted no refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun pokingABlockWithAnInertItemSaysNothing(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40PokeA")
        val bob = MessageCapturingPlayer.join(helper, "T40PokeB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.STONE)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.messages.clear()

        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIAMOND_SWORD))
        bob.usesHeldItemOn(helper, STONE_AT)
        helper.assertFalse(bob.wasRefusedBy("T40PokeA's Place"), "a sword on stone earned a refusal")
        helper.assertTrue(bob.messages.isEmpty(), "poking a block with a sword emitted a message")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    // ---- entities ----

    @GameTest
    fun aNonMemberCannotHitTheAnimals(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14CowA")
        val bob = MessageCapturingPlayer.join(helper, "T14CowB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(2, 2, 2))
        bob.standAt(helper, 2.0, 2.0, 2.0)

        bob.attacks(cow)
        helper.assertValueEqual(cow.health, cow.maxHealth, "a stranger hurt a protected animal")
        helper.assertValueEqual(bob.messages.last(), protectedBy("T14CowA's Place"), "the attack refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberCannotUseATamedWolfToHarmAnimals(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14WolfA")
        val bob = MessageCapturingPlayer.join(helper, "T14WolfB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(2, 2, 2))
        val wolf = helper.spawnWithNoFreeWill(EntityTypes.WOLF, BlockPos(2, 2, 1))
        wolf.tame(bob)
        bob.standAt(helper, 4.25, 2.0, 2.0)
        bob.messages.clear()

        helper.assertFalse(wolf.doHurtTarget(helper.level, cow), "a tamed wolf hurt a protected animal")
        helper.assertValueEqual(cow.health, cow.maxHealth, "a tamed wolf damaged a protected animal")
        helper.assertTrue(bob.wasRefusedBy("T14WolfA's Place"), "the tamed-wolf damage emitted no refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun anUntamedWolfCanHarmAnimalsInAProtectedRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14WildWolfA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(2, 2, 2))
        val wolf = helper.spawnWithNoFreeWill(EntityTypes.WOLF, BlockPos(2, 2, 1))

        helper.assertTrue(wolf.doHurtTarget(helper.level, cow), "an untamed wolf could not hurt a protected animal")
        helper.assertTrue(cow.health < cow.maxHealth, "an untamed wolf did not damage a protected animal")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberCannotAttackAnimalsOrPlayers(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14AttackA")
        val bob = MessageCapturingPlayer.join(helper, "T14AttackB")
        val charlie = MessageCapturingPlayer.join(helper, "T14AttackC")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(2, 2, 2))
        charlie.standAt(helper, 2.0, 2.0, 3.0)
        // The attacker is just beyond the region's eastern edge, but every
        // target remains close enough for the server's attack-range check.
        bob.standAt(helper, 4.25, 2.0, 2.0)
        bob.messages.clear()

        bob.attacks(cow)
        bob.attacks(charlie)

        helper.assertValueEqual(cow.health, cow.maxHealth, "a stranger hurt a protected cow")
        helper.assertValueEqual(charlie.health, charlie.maxHealth, "a stranger hurt a protected player")
        helper.assertTrue(bob.wasRefusedBy("T14AttackA's Place"), "no entity-attack refusal")
        alice.leave()
        bob.leave()
        charlie.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberMayCullAnUnnamedHostileButNotANamedOne(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40CullA")
        val bob = MessageCapturingPlayer.join(helper, "T40CullB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, BlockPos(2, 2, 2))
        val named = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, BlockPos(3, 2, 2))
        named.customName = net.minecraft.network.chat.Component.literal("Kevin")
        bob.standAt(helper, 4.25, 2.0, 2.0)
        bob.messages.clear()

        bob.attacks(zombie)
        helper.assertTrue(zombie.health < zombie.maxHealth, "a non-member could not cull an unnamed hostile")
        helper.assertFalse(bob.wasRefusedBy("T40CullA's Place"), "culling an unnamed hostile drew a refusal")

        bob.attacks(named)
        helper.assertValueEqual(named.health, named.maxHealth, "a non-member hit a named hostile")
        helper.assertTrue(bob.wasRefusedBy("T40CullA's Place"), "hitting a named hostile drew no refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberMayRideAHorseButNotAttackIt(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40HorseA")
        val bob = MessageCapturingPlayer.join(helper, "T40HorseB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val horse = helper.spawnWithNoFreeWill(EntityTypes.HORSE, BlockPos(2, 2, 2))
        horse.setTamed(true)
        bob.standAt(helper, 2.0, 2.0, 2.0)
        bob.messages.clear()

        bob.interactsWith(horse) // empty hand, not crouching → mount
        helper.assertFalse(bob.wasRefusedBy("T40HorseA's Place"), "a non-member could not mount a horse")

        bob.stopRiding()
        bob.attacks(horse)
        helper.assertValueEqual(horse.health, horse.maxHealth, "a non-member hurt a horse")
        helper.assertTrue(bob.wasRefusedBy("T40HorseA's Place"), "hitting a horse drew no refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aChestedDonkeyIsNotRiddenByANonMember(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40DonkA")
        val bob = MessageCapturingPlayer.join(helper, "T40DonkB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val donkey = helper.spawnWithNoFreeWill(EntityTypes.DONKEY, BlockPos(2, 2, 2))
        donkey.setTamed(true)
        donkey.setChest(true)
        bob.standAt(helper, 2.0, 2.0, 2.0)
        bob.messages.clear()

        bob.interactsWith(donkey) // empty hand, not crouching → would mount
        helper.assertFalse(bob.isPassenger, "a non-member rode a chested donkey")
        helper.assertTrue(bob.wasRefusedBy("T40DonkA's Place"), "riding a chested donkey drew no refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun projectilesCannotDamageAnyEntityInAnotherPlayersRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14ArrowA")
        val bob = MessageCapturingPlayer.join(helper, "T14ArrowB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        createRegion(helper, bob, 6.0 to 0.0, 10.0 to 4.0)
        val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(2, 2, 2))
        val unprotectedCow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(5, 2, 2))
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(3, 2, 2)), Direction.NORTH)
        frame.setItem(ItemStack(Items.DIAMOND))
        helper.level.addFreshEntity(frame)
        bob.standAt(helper, 8.0, 2.0, 2.0)
        bob.messages.clear()
        bob.shoots(helper, unprotectedCow)
        bob.shoots(helper, cow)
        bob.shoots(helper, frame)

        helper.assertTrue(
            unprotectedCow.health < unprotectedCow.maxHealth,
            "the arrow control did no damage outside a region",
        )
        helper.assertValueEqual(cow.health, cow.maxHealth, "an arrow hurt an animal in another player's region")
        helper.assertFalse(frame.isRemoved, "an arrow destroyed an item frame in another player's region")
        helper.assertTrue(ItemStack.matches(frame.item, ItemStack(Items.DIAMOND)), "an arrow removed the framed item")
        helper.assertTrue(bob.wasRefusedBy("T14ArrowA's Place"), "the projectile damage emitted no refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun disablingAnimalProtectionOpensTheHunt(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14AnimA")
        val bob = MessageCapturingPlayer.join(helper, "T14AnimB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_ANIMAL_PROTECTION")
        val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(2, 2, 2))
        bob.standAt(helper, 2.0, 2.0, 2.0)

        bob.attacks(cow)
        helper.assertTrue(cow.health < cow.maxHealth, "DISABLE_ANIMAL_PROTECTION still shielded the animal")
        helper.assertFalse(bob.wasRefusedBy("T14AnimA's Place"), "the unprotected animal still refused the hit")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun anEmptyHandedStrangerCannotRotateAnItemFrame(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14FrameA")
        val bob = MessageCapturingPlayer.join(helper, "T14FrameB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.NORTH)
        helper.level.addFreshEntity(frame)
        frame.setItem(ItemStack(Items.DIAMOND))
        val frameRotation = frame.rotation
        val frameItem = frame.item.copy()
        bob.standAt(helper, 4.25, 2.0, 2.0)
        bob.messages.clear()

        bob.interactsWith(frame, secondaryAction = false)

        helper.assertValueEqual(frame.rotation, frameRotation, "a stranger rotated an item frame")
        helper.assertTrue(ItemStack.matches(frame.item, frameItem), "a stranger changed item-frame contents")
        helper.assertValueEqual(
            bob.messages.last(),
            protectedBy("T14FrameA's Place"),
            "the item-frame interaction refusal",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun nothingButAMemberBreaksAnItemFrameInARegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40FrameBoom")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.NORTH)
        helper.level.addFreshEntity(frame)
        frame.setItem(ItemStack(Items.DIAMOND))
        val creeper = helper.spawnWithNoFreeWill(EntityTypes.CREEPER, BlockPos(2, 2, 4))

        // A blast, a skeleton's arrow, a stray snowball — nothing without a
        // member behind it may hurt the frame.
        frame.hurtServer(helper.level, helper.level.damageSources().explosion(null, creeper), 200.0f)
        frame.hurtServer(helper.level, helper.level.damageSources().mobAttack(creeper), 200.0f)

        helper.assertFalse(frame.isRemoved, "a non-member force broke an item frame in a region")
        helper.assertTrue(ItemStack.matches(frame.item, ItemStack(Items.DIAMOND)), "the framed item was knocked out")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aMemberBreaksTheirOwnItemFrame(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T40FrameMine")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.NORTH)
        helper.level.addFreshEntity(frame) // left empty: one hit breaks it
        alice.standAt(helper, 2.0, 2.0, 3.0)
        alice.messages.clear()

        alice.attacks(frame)
        helper.assertTrue(frame.isRemoved, "a member could not break their own item frame")
        helper.assertFalse(alice.wasRefusedBy("T40FrameMine's Place"), "a member was refused their own item frame")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun anEmptyHandedStrangerCannotRemoveArmorStandEquipment(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14ArmorA")
        val bob = MessageCapturingPlayer.join(helper, "T14ArmorB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val armorAt = helper.absolutePos(BlockPos(3, 2, 2))
        val armorStand = ArmorStand(helper.level, armorAt.x.toDouble(), armorAt.y.toDouble(), armorAt.z.toDouble())
        helper.level.addFreshEntity(armorStand)
        armorStand.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.DIAMOND))
        val head = armorStand.getItemBySlot(EquipmentSlot.HEAD).copy()
        bob.standAt(helper, 4.25, 2.0, 2.0)
        bob.messages.clear()

        bob.isShiftKeyDown = true
        bob.interactsWith(armorStand, secondaryAction = true)
        helper.assertTrue(
            ItemStack.matches(armorStand.getItemBySlot(EquipmentSlot.HEAD), head),
            "a stranger removed armor-stand equipment",
        )
        helper.assertValueEqual(
            bob.messages.last(),
            protectedBy("T14ArmorA's Place"),
            "the armor-stand interaction refusal",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun anEmptyHandedInteractionIsLeftAlone(helper: GameTestHelper) {
        // How a villager is traded with — the Portal deliberately let it pass.
        val alice = MessageCapturingPlayer.join(helper, "T14TradeA")
        val bob = MessageCapturingPlayer.join(helper, "T14TradeB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(2, 2, 2))
        bob.standAt(helper, 2.0, 2.0, 2.0)

        bob.interactsWith(cow)
        helper.assertFalse(bob.wasRefusedBy("T14TradeA's Place"), "an empty-handed interaction was refused")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aHeldItemInteractionIsRefused(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14HeldA")
        val bob = MessageCapturingPlayer.join(helper, "T14HeldB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(2, 2, 2))
        bob.standAt(helper, 2.0, 2.0, 2.0)
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.BUCKET))

        bob.interactsWith(cow)
        helper.assertValueEqual(bob.mainHandItem.item, Items.BUCKET, "a stranger milked a protected cow")
        helper.assertValueEqual(
            bob.messages.last(),
            protectedBy("T14HeldA's Place"),
            "the held-item interaction refusal",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun publicVillagerTradingOpensHeldItemInteraction(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14VillA")
        val bob = MessageCapturingPlayer.join(helper, "T14VillB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag ENABLE_PUBLIC_VILLAGER_TRADING")
        val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(2, 2, 2))
        bob.standAt(helper, 2.0, 2.0, 2.0)
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.BUCKET))

        bob.interactsWith(cow)
        helper.assertValueEqual(
            bob.mainHandItem.item,
            Items.MILK_BUCKET,
            "ENABLE_PUBLIC_VILLAGER_TRADING still refused a held-item interaction",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    // ---- protection is always current ----

    @GameTest
    fun aNewMemberMayBuildAtOnce(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14AddA")
        val bob = MessageCapturingPlayer.join(helper, "T14AddB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.STONE)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        helper.assertFalse(bob.digs(helper), "precondition: the stranger could already dig")

        alice.runCommand("rg add T14AddB")
        helper.assertTrue(bob.digs(helper), "a freshly added member was still refused")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aRemovedMemberIsRefusedAtOnce(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14RemA")
        val bob = MessageCapturingPlayer.join(helper, "T14RemB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.runCommand("rg add T14RemB")
        helper.setBlock(STONE_AT, Blocks.STONE)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        helper.assertTrue(bob.digs(helper), "precondition: the member could not dig")

        alice.runCommand("rg remove T14RemB")
        helper.setBlock(STONE_AT, Blocks.STONE)
        helper.assertFalse(bob.digs(helper), "a removed member could still dig")
        helper.assertValueEqual(
            bob.messages.last(),
            protectedBy("T14RemA's Place"),
            "the removed member's refusal",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun arrivingByTeleportIsProtectedImmediately(helper: GameTestHelper) {
        // Deviation 9: the Portal only knew where a player was from their
        // movement packets, so a teleport in landed them unprotected.
        val alice = MessageCapturingPlayer.join(helper, "T14TpA")
        val bob = MessageCapturingPlayer.join(helper, "T14TpB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(STONE_AT, Blocks.STONE)
        bob.standAt(helper, 8.0, 2.0, 8.0)

        val arrival = helper.absoluteVec(Vec3(2.0, 2.0, 1.0))
        bob.teleportTo(arrival.x, arrival.y, arrival.z)
        helper.assertFalse(bob.digs(helper), "a player who teleported in dug before anyone noticed")
        helper.assertValueEqual(
            bob.messages.last(),
            protectedBy("T14TpA's Place"),
            "the refusal after a teleport in",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest(maxTicks = 100)
    fun aStrangerUsesAdventureModeOnlyWhileInsideAnotherPlayersRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14AdvA")
        val bob = MessageCapturingPlayer.join(helper, "T14AdvB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        bob.standAt(helper, 7.0, 2.0, 2.0)
        helper.assertValueEqual(bob.gameMode.gameModeForPlayer, GameType.SURVIVAL, "the starting game mode")

        bob.standAt(helper, 2.0, 2.0, 2.0)
        helper.runAfterDelay(2) {
            try {
                helper.assertValueEqual(
                    bob.gameMode.gameModeForPlayer,
                    GameType.ADVENTURE,
                    "a stranger's game mode inside another player's region",
                )

                alice.runCommand("rg add T14AdvB")
                helper.assertValueEqual(
                    bob.gameMode.gameModeForPlayer,
                    GameType.SURVIVAL,
                    "the game mode after gaining membership without moving",
                )
                alice.runCommand("rg remove T14AdvB")
                helper.assertValueEqual(
                    bob.gameMode.gameModeForPlayer,
                    GameType.ADVENTURE,
                    "the game mode after losing membership without moving",
                )
                bob.standAt(helper, 7.0, 2.0, 2.0)
            } catch (failure: Throwable) {
                alice.leave()
                bob.leave()
                throw failure
            }
        }
        helper.runAfterDelay(4) {
            try {
                helper.assertValueEqual(
                    bob.gameMode.gameModeForPlayer,
                    GameType.SURVIVAL,
                    "the game mode after leaving the foreign region",
                )
                bob.standAt(helper, 2.0, 2.0, 2.0)
            } catch (failure: Throwable) {
                alice.leave()
                bob.leave()
                throw failure
            }
        }
        helper.runAfterDelay(6) {
            try {
                helper.assertValueEqual(
                    bob.gameMode.gameModeForPlayer,
                    GameType.ADVENTURE,
                    "the game mode after returning to the foreign region",
                )
                alice.runCommand("rg delete")
                helper.assertValueEqual(
                    bob.gameMode.gameModeForPlayer,
                    GameType.SURVIVAL,
                    "the game mode after the foreign region was deleted",
                )
                helper.succeed()
            } finally {
                alice.leave()
                bob.leave()
            }
        }
    }
}

/** The block every dig and place test works on, inside the test region. */
private val STONE_AT = BlockPos(2, 2, 2)

/** The block placed against, one below [STONE_AT]. */
private val FLOOR_AT = BlockPos(2, 1, 2)

/** A block inside the parent region but outside the sub-region. */
private val OUTER_AT = BlockPos(0, 2, 0)

/** Where the container tests put their chest. */
private val CHEST_AT = BlockPos(1, 2, 3)

/** Where the sign tests put their sign. */
private val SIGN_AT = BlockPos(3, 2, 1)

/** Breaks [at] the way a finished dig does. */
private fun MessageCapturingPlayer.digs(helper: GameTestHelper, at: BlockPos = STONE_AT): Boolean =
    gameMode.destroyBlock(helper.absolutePos(at))

/** Sends the first packet of a dig — the whole of it for an instant break. */
private fun MessageCapturingPlayer.startsDigging(helper: GameTestHelper, at: BlockPos = STONE_AT) {
    gameMode.handleBlockBreakAction(
        helper.absolutePos(at),
        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
        Direction.UP,
        helper.level.maxY,
        0,
    )
}

/** Places a stone block on top of [floor]. */
private fun MessageCapturingPlayer.placesOn(helper: GameTestHelper, floor: BlockPos): InteractionResult {
    val target = helper.absolutePos(floor)
    val hit = BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false)
    setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.STONE))
    return gameMode.useItemOn(this, level(), mainHandItem, InteractionHand.MAIN_HAND, hit)
}

/** Uses whatever is in the main hand; false when the server refused the use. */
private fun MessageCapturingPlayer.usesHeldItem(): Boolean =
    gameMode.useItem(this, level(), mainHandItem, InteractionHand.MAIN_HAND) != InteractionResult.FAIL

/** Uses the held item against [at], as a client does while looking at a block. */
private fun MessageCapturingPlayer.usesHeldItemOn(helper: GameTestHelper, at: BlockPos): Boolean {
    val target = helper.absolutePos(at)
    val hit = BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false)
    return gameMode.useItemOn(this, level(), mainHandItem, InteractionHand.MAIN_HAND, hit) != InteractionResult.FAIL
}

/** Puts a sign in the world that [editor] is the one allowed to write on. */
private fun GameTestHelper.placeSignFor(editor: MessageCapturingPlayer): SignBlockEntity {
    setBlock(SIGN_AT, Blocks.OAK_SIGN)
    val sign = level.getBlockEntity(absolutePos(SIGN_AT)) as SignBlockEntity
    sign.setAllowedPlayerEditor(editor.uuid)
    return sign
}

/** Sends the sign text a client sends when the editor presses Done. */
private fun MessageCapturingPlayer.writesOnSign(helper: GameTestHelper, first: String, second: String) {
    connection.handleSignUpdate(
        ServerboundSignUpdatePacket(helper.absolutePos(SIGN_AT), true, first, second, "", ""),
    )
}

private fun signLine(sign: SignBlockEntity): String = sign.frontText.getMessage(0, false).string

/** A chest holding one diamond, inside the test region. */
private fun GameTestHelper.stockedChest(): ChestBlockEntity {
    setBlock(CHEST_AT, Blocks.CHEST)
    val chest = level.getBlockEntity(absolutePos(CHEST_AT)) as ChestBlockEntity
    chest.setItem(0, ItemStack(Items.DIAMOND))
    return chest
}

private fun MessageCapturingPlayer.opensChest(helper: GameTestHelper) {
    openMenu(helper.level.getBlockEntity(helper.absolutePos(CHEST_AT)) as ChestBlockEntity)
}
private fun MessageCapturingPlayer.opensEnderChest() {
    openMenu(
        SimpleMenuProvider(
            { containerId, inventory, _ ->
                ChestMenu.threeRows(containerId, inventory, enderChestInventory)
            },
            Component.literal("Ender Chest"),
        ),
    )
}


private fun MessageCapturingPlayer.clicksFirstSlot() {
    containerMenu.clicked(0, 0, ContainerInput.PICKUP, this)
}

private fun MessageCapturingPlayer.attacks(target: Entity) {
    connection.handleAttack(ServerboundAttackPacket(target.id))
}
private fun MessageCapturingPlayer.interactsWith(target: Entity, secondaryAction: Boolean = false) {
    connection.handleInteract(
        ServerboundInteractPacket(target.id, InteractionHand.MAIN_HAND, Vec3.ZERO, secondaryAction),
    )
}

/** Drives the same hit hook vanilla arrows call after resolving a collision. */
private fun MessageCapturingPlayer.shoots(helper: GameTestHelper, target: Entity) {
    val arrow = TestArrow(helper.level, this)
    arrow.shoot(1.0, 0.0, 0.0, 1.0f, 0.0f)
    helper.level.addFreshEntity(arrow)
    arrow.hit(target)
}

private class TestArrow(level: ServerLevel, shooter: MessageCapturingPlayer) :
    Arrow(level, shooter, ItemStack(Items.ARROW), ItemStack(Items.BOW)) {
    fun hit(target: Entity) {
        onHitEntity(EntityHitResult(target))
    }
}
