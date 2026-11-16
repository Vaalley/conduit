package eu.mctraveler.gametest

import eu.mctraveler.crystal.CrystalSpawns
import eu.mctraveler.rtp.PortalBox
import eu.mctraveler.rtp.PortalSelector
import eu.mctraveler.rtp.RtpConfig
import eu.mctraveler.rtp.RtpCooldown
import eu.mctraveler.rtp.WildernessPortals
import kotlin.math.hypot
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/** Wilderness portals: the selector brush, the water they are made of, and walking into one. */
class WildernessPortalGameTest {

    private fun MessageCapturingPlayer.leftClicks(helper: GameTestHelper, at: BlockPos) {
        AttackBlockCallback.EVENT.invoker().interact(this, helper.level, InteractionHand.MAIN_HAND, helper.absolutePos(at), Direction.UP)
    }

    private fun MessageCapturingPlayer.rightClicks(helper: GameTestHelper, at: BlockPos) {
        val target = helper.absolutePos(at)
        UseBlockCallback.EVENT.invoker().interact(
            this,
            helper.level,
            InteractionHand.MAIN_HAND,
            BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false),
        )
    }

    @GameTest(maxTicks = 100)
    fun theSelectorBuildsAWaterPortalAndRemoveDrainsIt(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "PortalBuilder")
        try {
            admin.makeAdmin()
            admin.standAt(helper, 1.0, 1.0, 1.0)
            admin.runCommand("wilderness portal create")
            val tool = admin.inventory.getNonEquipmentItems().firstOrNull { PortalSelector.isTool(it) }
            helper.assertTrue(tool != null, "the Portal selector was not handed over")
            helper.assertValueEqual(tool!!.hoverName.string, "Portal selector", "the tool name")
            admin.setItemInHand(InteractionHand.MAIN_HAND, tool)
            admin.messages.clear()

            val first = BlockPos(3, 2, 3)
            val second = BlockPos(4, 3, 4)
            admin.leftClicks(helper, first)
            helper.assertTrue(admin.messages.any { it.string.startsWith("First position set") }, "no feedback for the first corner")
            admin.rightClicks(helper, second)
            helper.assertTrue(admin.messages.any { it.string.contains("Second position set") && it.string.contains("8 blocks") }, "no size in the second feedback")
            helper.assertTrue(admin.messages.any { it.string.contains("[Accept]") }, "no prompt to accept")

            admin.runCommand("wilderness portal accept")
            helper.assertBlockPresent(Blocks.WATER, first)
            helper.assertBlockPresent(Blocks.WATER, second)
            helper.assertTrue(WildernessPortals.all().any { it.box.volume == 8L }, "the portal was not recorded")

            val inside = helper.absolutePos(first)
            val outside = helper.absolutePos(BlockPos(5, 2, 3))
            helper.assertTrue(WildernessPortals.blocksSpread(helper.level, inside, outside), "water may flow out of the portal")
            helper.assertTrue(!WildernessPortals.blocksSpread(helper.level, inside, helper.absolutePos(BlockPos(4, 2, 3))), "water was stopped inside the portal")

            admin.standAt(helper, 3.0, 2.0, 1.0)
            admin.runCommand("wilderness portal remove")
            helper.assertBlockNotPresent(Blocks.WATER, first)
            helper.assertTrue(WildernessPortals.all().none { it.box.volume == 8L && it.box.contains(helper.absolutePos(first)) }, "the portal is still recorded")
            helper.succeed()
        } finally {
            WildernessPortals.all().filter { it.box.contains(helper.absolutePos(BlockPos(3, 2, 3))) }
                .forEach { WildernessPortals.remove(helper.level, it) }
            admin.leave()
        }
    }

    @GameTest
    fun thePortalCommandsAreHiddenFromOrdinaryPlayers(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "PortalPlain")
        try {
            val wilderness = helper.level.server.commands.dispatcher.root.getChild("wilderness")
            val portal = wilderness?.getChild("portal")
            helper.assertTrue(portal != null && !portal.canUse(player.createCommandSourceStack()), "/wilderness portal is visible to a player")
            helper.assertTrue(wilderness!!.canUse(player.createCommandSourceStack()), "/wilderness itself should stay public")
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest(maxTicks = 100)
    fun walkingIntoAPortalSendsYouToTheWilderness(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "PortalWalker")
        RtpCooldown.forget(player.uuid)
        val box = PortalBox.of(helper.absolutePos(BlockPos(1, 2, 1)), helper.absolutePos(BlockPos(3, 4, 3)))
        val portal = WildernessPortals.create(helper.level, box)
        player.standAt(helper, 2.0, 2.0, 2.0)
        val start = player.position()

        helper.runAfterDelay(8) {
            try {
                val spawn = CrystalSpawns.definitions()[1]
                val distance = hypot(player.x - spawn.x, player.z - spawn.z)
                helper.assertTrue(player.position() != start, "the portal did not move the player")
                helper.assertTrue(distance >= RtpConfig.settings().minDistance - 1, "landed only $distance from the second spawn")
                helper.assertTrue(distance <= RtpConfig.settings().radius + 1, "landed $distance from the second spawn")
                helper.assertTrue(
                    RtpCooldown.remaining(player.uuid, helper.level.server.tickCount) > 0,
                    "the portal did not start the cooldown",
                )
                helper.succeed()
            } finally {
                WildernessPortals.remove(helper.level, portal)
                RtpCooldown.forget(player.uuid)
                player.leave()
            }
        }
    }
}
