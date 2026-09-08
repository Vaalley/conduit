package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.moderation.ActionRecorder
import eu.mctraveler.moderation.ActionType
import eu.mctraveler.region.RegionWorlds
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

class ActionLogGameTest {

    @GameTest(maxTicks = 140)
    fun breakPlaceContainerInspectAndLookupAreRecorded(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = MessageCapturingPlayer.join(helper, "ActionAdmin").also { it.makeAdmin() }
        val subject = TestPlayer.join(server, "ActionSubject")
        val breakPos = helper.absolutePos(BlockPos(1, 2, 1))
        val supportPos = helper.absolutePos(BlockPos(3, 1, 1))
        val placePos = helper.absolutePos(BlockPos(3, 2, 1))
        val chestPos = helper.absolutePos(BlockPos(5, 1, 1))
        helper.setBlock(breakPos, Blocks.STONE)
        helper.setBlock(supportPos, Blocks.STONE)
        helper.setBlock(chestPos, Blocks.CHEST)
        subject.player.setPos(breakPos.x + .5, breakPos.y + 1.0, breakPos.z + .5)
        admin.setPos(chestPos.x + 2.0, chestPos.y + 1.0, chestPos.z + .5)
        val actions = checkNotNull(MCTraveler.persistence?.actions)

        helper.runAfterDelay(2) {
            helper.assertTrue(subject.player.gameMode.destroyBlock(breakPos), "subject could not break the test block")
            PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(
                subject.player.level(),
                subject.player,
                breakPos,
                Blocks.STONE.defaultBlockState(),
                null,
            )
            subject.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIRT))
            ActionRecorder.queuePlacement(
                subject.player,
                subject.player.level(),
                BlockHitResult(Vec3.atCenterOf(placePos), Direction.UP, placePos, false),
            )
            helper.assertTrue(subject.player.level().getBlockState(placePos).isAir, "placement target was not air")
            subject.player.level().setBlock(placePos, Blocks.DIRT.defaultBlockState(), 3)
            helper.assertTrue(
                subject.player.level().getBlockState(placePos).block == Blocks.DIRT,
                "placement target was not set",
            )
            ActionRecorder.flushPendingPlacements()
            UseBlockCallback.EVENT.invoker().interact(
                subject.player,
                subject.player.level(),
                InteractionHand.MAIN_HAND,
                BlockHitResult(Vec3.atCenterOf(chestPos), Direction.UP, chestPos, false),
            )
            actions.record(
                ActionType.CONTAINER,
                subject.player.uuid,
                subject.player.gameProfile.name,
                RegionWorlds.legacyName(subject.player.level().dimension()),
                chestPos.x,
                chestPos.y,
                chestPos.z,
                "minecraft:chest",
            )
        }
        helper.runAfterDelay(5) {
            val world = RegionWorlds.legacyName(helper.level.dimension())
            helper.assertTrue(
                actions.at(world, breakPos.x, breakPos.y, breakPos.z).any { it.type == ActionType.BREAK },
                "break was not logged: ${actions.all()}",
            )
            helper.assertTrue(
                actions.at(world, placePos.x, placePos.y, placePos.z).any { it.type == ActionType.PLACE },
                "place was not logged: ${actions.all()}",
            )
            helper.assertTrue(actions.at(world, chestPos.x, chestPos.y, chestPos.z).any { it.type == ActionType.CONTAINER }, "container was not logged")

            admin.runCommand("inspect")
            val inspectResult = AttackBlockCallback.EVENT.invoker().interact(
                admin,
                admin.level(),
                InteractionHand.MAIN_HAND,
                breakPos,
                Direction.UP,
            )
            helper.assertValueEqual(inspectResult, InteractionResult.FAIL, "inspect interaction result")
            helper.assertTrue(admin.messages.any { it.string.contains("broke") }, "inspect output was ${admin.messages}")

            admin.messages.clear()
            admin.runCommand("lookup ActionSubject")
            helper.assertTrue(admin.messages.any { it.string.contains("broke") }, "player lookup was ${admin.messages}")
            admin.messages.clear()
            admin.runCommand("lookup r:5")
            helper.assertTrue(admin.messages.any { it.string.contains("opened") || it.string.contains("broke") }, "radius lookup was ${admin.messages}")
            admin.messages.clear()
            admin.runCommand("lookup ActionAdmin 1h place")
            helper.assertTrue(admin.messages.any { it.string == "No matching actions" }, "filtered lookup was ${admin.messages}")

            admin.leave()
            subject.disconnect()
            helper.succeed()
        }
    }
}
