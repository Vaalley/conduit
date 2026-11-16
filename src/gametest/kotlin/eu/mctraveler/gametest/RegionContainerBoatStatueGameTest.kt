package eu.mctraveler.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Three corners of region protection: a container is judged by where it is, not where the
 * player stands; a stranger cannot shove a chest boat out of a region; and a copper golem
 * statue is not the stranger's to re-pose.
 */
class RegionContainerBoatStatueGameTest {

    private fun rightClick(helper: GameTestHelper, player: MessageCapturingPlayer, at: BlockPos): InteractionResult {
        val target = helper.absolutePos(at)
        val hit = BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false)
        return player.gameMode.useItemOn(player, player.level(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit)
    }

    @GameTest
    fun aChestOutsideTheRegionOpensForSomeoneStandingInsideAForeignOne(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T20OutA")
        val bob = MessageCapturingPlayer.join(helper, "T20OutB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val chestAt = BlockPos(7, 2, 2) // outside the region
        helper.setBlock(chestAt, Blocks.CHEST)
        (helper.level.getBlockEntity(helper.absolutePos(chestAt)) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND))
        bob.standAt(helper, 2.0, 2.0, 2.0) // inside Alice's region
        bob.messages.clear()

        rightClick(helper, bob, chestAt)
        helper.assertTrue(bob.containerMenu is ChestMenu, "the chest outside the region did not open")
        bob.containerMenu.clicked(0, 0, ContainerInput.PICKUP, bob)
        helper.assertFalse(bob.containerMenu.carried.isEmpty, "a chest outside any region was refused from inside a foreign one")
        helper.assertFalse(bob.wasRefusedBy("T20OutA's Place"), "the region refused a chest that is not in it")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aChestInsideTheRegionStaysClosedToAStrangerStandingOutside(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T20InA")
        val bob = MessageCapturingPlayer.join(helper, "T20InB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val chestAt = BlockPos(1, 2, 3)
        helper.setBlock(chestAt, Blocks.CHEST)
        (helper.level.getBlockEntity(helper.absolutePos(chestAt)) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND))
        bob.standAt(helper, 7.0, 2.0, 2.0) // outside
        bob.messages.clear()

        rightClick(helper, bob, chestAt)
        bob.containerMenu.clicked(0, 0, ContainerInput.PICKUP, bob)
        helper.assertTrue(bob.containerMenu.carried.isEmpty, "a stranger took from a protected chest")
        helper.assertTrue(bob.wasRefusedBy("T20InA's Place"), "no protection message for a chest inside the region")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aStrangerCannotPushAChestBoatButAMemberCan(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T20BoatA")
        val bob = MessageCapturingPlayer.join(helper, "T20BoatB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val boat = helper.spawn(EntityTypes.OAK_CHEST_BOAT, BlockPos(2, 2, 2))
        bob.standAt(helper, 2.4, 2.0, 2.0)
        alice.standAt(helper, 2.4, 2.0, 2.0)

        boat.deltaMovement = Vec3.ZERO
        boat.push(bob)
        helper.assertTrue(boat.deltaMovement.lengthSqr() == 0.0, "a stranger pushed a chest boat inside a region")

        boat.push(alice)
        helper.assertTrue(boat.deltaMovement.lengthSqr() > 0.0, "a member could not push their own chest boat")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aStrangerCannotRePoseACopperGolemStatue(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T20StatA")
        val bob = MessageCapturingPlayer.join(helper, "T20StatB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        val statueAt = BlockPos(2, 2, 2)
        helper.setBlock(statueAt, Blocks.COPPER_GOLEM_STATUE.asList().first())
        bob.standAt(helper, 2.0, 2.0, 0.0)
        bob.messages.clear()

        val result = rightClick(helper, bob, statueAt)
        helper.assertTrue(result == InteractionResult.FAIL, "a stranger used a copper golem statue, got $result")
        helper.assertTrue(bob.wasRefusedBy("T20StatA's Place"), "no protection message for the statue")

        alice.standAt(helper, 2.0, 2.0, 0.0)
        helper.assertTrue(rightClick(helper, alice, statueAt) != InteractionResult.FAIL, "the owner was refused their own statue")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aStrangerPlacesABoatWhenBoatsIsOn(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T20PlaceA")
        val bob = MessageCapturingPlayer.join(helper, "T20PlaceB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(BlockPos(2, 1, 2), Blocks.STONE)
        helper.setBlock(BlockPos(2, 2, 2), Blocks.WATER)
        bob.standAt(helper, 2.5, 2.0, 0.5)
        bob.setGameMode(net.minecraft.world.level.GameType.ADVENTURE)
        bob.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, helper.absoluteVec(Vec3(2.5, 2.5, 2.5)))
        val area = net.minecraft.world.phys.AABB(helper.absolutePos(BlockPos(2, 2, 2))).inflate(3.0)
        bob.messages.clear()

        bob.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.OAK_BOAT))
        // The client sends the click on the block under the water first, then the plain use.
        val floor = helper.absolutePos(BlockPos(2, 1, 2))
        val onFloor = bob.gameMode.useItemOn(
            bob, bob.level(), bob.mainHandItem, InteractionHand.MAIN_HAND,
            BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false),
        )
        helper.assertTrue(onFloor != InteractionResult.FAIL, "the click on the floor was refused: $onFloor")
        val result = bob.gameMode.useItem(bob, bob.level(), bob.mainHandItem, InteractionHand.MAIN_HAND)
        val placed = helper.level.getEntitiesOfClass(net.minecraft.world.entity.vehicle.boat.AbstractBoat::class.java, area)
        helper.assertTrue(placed.isNotEmpty(), "no boat was placed, result $result, messages ${bob.messages.map { it.string }}")
        alice.leave()
        bob.leave()
        helper.succeed()
    }
}
