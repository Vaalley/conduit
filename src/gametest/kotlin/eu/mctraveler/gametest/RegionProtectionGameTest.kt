package eu.mctraveler.gametest

import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.phys.BlockHitResult
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

        helper.assertFalse(bob.digs(helper), "a non-member broke a block in someone else's region")
        helper.assertBlockPresent(Blocks.STONE, STONE_AT)
        helper.assertValueEqual(bob.messages.last(), protectedBy("T14DigA's Place"), "the dig refusal")
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
    fun aNonMemberCannotUseAnItem(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T14UseA")
        val bob = MessageCapturingPlayer.join(helper, "T14UseB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        bob.standAt(helper, 2.0, 1.0, 2.0)
        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.STICK))

        helper.assertFalse(bob.usesHeldItem(), "a stranger used an item inside a region")
        helper.assertValueEqual(bob.messages.last(), protectedBy("T14UseA's Place"), "the item-use refusal")
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

private fun MessageCapturingPlayer.clicksFirstSlot() {
    containerMenu.clicked(0, 0, ContainerInput.PICKUP, this)
}

private fun MessageCapturingPlayer.attacks(target: Entity) {
    connection.handleAttack(ServerboundAttackPacket(target.id))
}

private fun MessageCapturingPlayer.interactsWith(target: Entity) {
    connection.handleInteract(
        ServerboundInteractPacket(target.id, InteractionHand.MAIN_HAND, Vec3.ZERO, false),
    )
}
