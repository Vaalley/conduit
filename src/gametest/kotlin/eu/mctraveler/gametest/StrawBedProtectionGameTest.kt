package eu.mctraveler.gametest

import eu.mctraveler.region.RegionStrawBeds
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.AbstractBedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.properties.BedPart

/**
 * A straw bed (26.3) breaks itself when its sleeper gets up — a real vanilla
 * mechanic (`BedRule.destroyOnLeave`), not one this mod adds. Inside a
 * region, a stranger sleeping in the resident's straw bed should not be able
 * to trigger that self-destruct on their behalf; the resident (or anyone who
 * could otherwise change blocks there) still can, exactly as vanilla intends.
 *
 * Seam: [AbstractBedBlock.onStopSleeping] directly, the same public method a
 * real get-up eventually reaches, rather than driving a real sleep through
 * [net.minecraft.world.entity.player.Player.startSleepInBed] — that path is
 * gated by preconditions (night, no monster nearby, no obstruction) this
 * protection has nothing to do with, and that make a gametest fight the
 * clock for no benefit. What is under test starts one step later: whoever
 * [RegionStrawBeds] was told last sleeps here is who the region checks.
 */
class StrawBedProtectionGameTest {

    @GameTest
    fun aStrangersSleepDoesNotDestroyTheResidentsStrawBedInsideARegion(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "StrawStrangerOwnerA")
        val stranger = MessageCapturingPlayer.join(helper, "StrawStrangerA")
        val foot = BlockPos(2, 2, 2)
        try {
            createRegion(helper, owner, 0.0 to 0.0, 4.0 to 4.0)
            val pos = placeStrawBed(helper, foot)

            RegionStrawBeds.recordSleep(pos, stranger)
            (Blocks.STRAW_BED as AbstractBedBlock).onStopSleeping(helper.level, pos)

            helper.assertBlockPresent(Blocks.STRAW_BED, foot)
            helper.succeed()
        } finally {
            owner.leave()
            stranger.leave()
        }
    }

    @GameTest
    fun theResidentsOwnSleepStillDestroysTheirStrawBed(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "StrawStrangerOwnerB")
        val foot = BlockPos(2, 2, 2)
        try {
            createRegion(helper, owner, 0.0 to 0.0, 4.0 to 4.0)
            val pos = placeStrawBed(helper, foot)

            RegionStrawBeds.recordSleep(pos, owner)
            (Blocks.STRAW_BED as AbstractBedBlock).onStopSleeping(helper.level, pos)

            helper.assertBlockNotPresent(Blocks.STRAW_BED, foot)
            helper.succeed()
        } finally {
            owner.leave()
        }
    }

    @GameTest
    fun aStrawBedOutsideAnyRegionAlwaysBreaksAsVanillaIntends(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StrawStrangerC")
        val foot = BlockPos(2, 2, 2)
        try {
            val pos = placeStrawBed(helper, foot)

            RegionStrawBeds.recordSleep(pos, player)
            (Blocks.STRAW_BED as AbstractBedBlock).onStopSleeping(helper.level, pos)

            helper.assertBlockNotPresent(Blocks.STRAW_BED, foot)
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    /** Places a straw bed with its foot at [foot], facing north, and returns the foot's absolute position. */
    private fun placeStrawBed(helper: GameTestHelper, foot: BlockPos): BlockPos {
        val bed = Blocks.STRAW_BED.defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
        val pos = helper.absolutePos(foot)
        helper.level.setBlock(pos, bed.setValue(AbstractBedBlock.PART, BedPart.FOOT), Block.UPDATE_CLIENTS)
        helper.level.setBlock(
            helper.absolutePos(foot.north()),
            bed.setValue(AbstractBedBlock.PART, BedPart.HEAD),
            Block.UPDATE_CLIENTS,
        )
        return pos
    }
}
