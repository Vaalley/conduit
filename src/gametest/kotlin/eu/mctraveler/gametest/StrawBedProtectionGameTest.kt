package eu.mctraveler.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.world.clock.ClockTimeMarkers
import net.minecraft.world.clock.WorldClocks
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.AbstractBedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.StrawBedBlock
import net.minecraft.world.level.block.state.properties.BedPart

/**
 * A straw bed (26.3) breaks itself when its sleeper gets up — a real vanilla
 * mechanic (`BedRule.destroyOnLeave`), not one this mod adds. Inside a
 * region, a stranger sleeping in the resident's straw bed should not be able
 * to trigger that self-destruct on their behalf; the resident (or anyone who
 * could otherwise change blocks there) still can, exactly as vanilla intends.
 *
 * Seam: [net.minecraft.world.entity.player.Player.startSleepInBed] and
 * `stopSleepInBed` directly — the same two calls a real click-to-sleep and
 * get-up eventually make, without needing night time or the other sleep
 * preconditions (too far, obstructed, monsters nearby) that only
 * `useWithoutItem`'s own click path cares about.
 */
class StrawBedProtectionGameTest {

    @GameTest
    fun aStrangersSleepDoesNotDestroyTheResidentsStrawBedInsideARegion(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "StrawStrangerOwnerA")
        val stranger = MessageCapturingPlayer.join(helper, "StrawStrangerA")
        val foot = BlockPos(2, 2, 2)
        try {
            createRegion(helper, owner, 0.0 to 0.0, 4.0 to 4.0)
            placeStrawBed(helper, foot)
            stranger.standAt(helper, foot.x + 0.5, 2.0, foot.z + 0.5)

            sleepThenLeave(helper, foot, stranger)

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
            placeStrawBed(helper, foot)
            owner.standAt(helper, foot.x + 0.5, 2.0, foot.z + 0.5)

            sleepThenLeave(helper, foot, owner)

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
            placeStrawBed(helper, foot)
            player.standAt(helper, foot.x + 0.5, 2.0, foot.z + 0.5)

            sleepThenLeave(helper, foot, player)

            helper.assertBlockNotPresent(Blocks.STRAW_BED, foot)
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    /** Places a straw bed with its foot at [foot], facing north. */
    private fun placeStrawBed(helper: GameTestHelper, foot: BlockPos) {
        val bed = Blocks.STRAW_BED.defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
        helper.level.setBlock(
            helper.absolutePos(foot),
            bed.setValue(AbstractBedBlock.PART, BedPart.FOOT),
            Block.UPDATE_CLIENTS,
        )
        helper.level.setBlock(
            helper.absolutePos(foot.north()),
            bed.setValue(AbstractBedBlock.PART, BedPart.HEAD),
            Block.UPDATE_CLIENTS,
        )
    }

    /** [player] starts sleeping at the straw bed's [foot], then immediately gets up. */
    private fun sleepThenLeave(helper: GameTestHelper, foot: BlockPos, player: MessageCapturingPlayer) {
        atNight(helper) { sleepThenLeaveAtNight(helper, foot, player) }
    }

    /**
     * Vanilla only lets anyone sleep at night, whatever hour the test world is at, so the
     * overworld clock is moved to night for the duration and put back afterwards.
     */
    private fun atNight(helper: GameTestHelper, body: () -> Unit) {
        val clocks = helper.level.clockManager()
        val clock = helper.level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD)
        val before = clocks.getInstance(clock).totalTicks()
        clocks.moveToTimeMarker(clock, ClockTimeMarkers.NIGHT)
        try {
            body()
        } finally {
            clocks.setTotalTicks(clock, before)
        }
    }

    private fun sleepThenLeaveAtNight(helper: GameTestHelper, foot: BlockPos, player: MessageCapturingPlayer) {
        // Vanilla also refuses rest while a hostile mob is nearby, and neighbouring tests spawn
        // some; clear the ones close enough to the bed to count.
        val around = net.minecraft.world.phys.AABB(helper.absolutePos(foot)).inflate(8.0, 5.0, 8.0)
        helper.level.getEntities(null as net.minecraft.world.entity.Entity?, around) { it is net.minecraft.world.entity.monster.Enemy }
            .forEach { it.discard() }
        val pos = helper.absolutePos(foot)
        val block = Blocks.STRAW_BED as StrawBedBlock
        val state = helper.level.getBlockState(pos)
        val bedRule = block.getBedRule(helper.level, pos)
        val started = player.startSleepInBed(block, state, bedRule, pos)
        helper.assertTrue(started.right().isPresent, "the player could not start sleeping in the straw bed: ${started.left().map { it.message()?.string ?: "?" }.orElse("?")}")
        player.stopSleepInBed(true, true)
    }
}
