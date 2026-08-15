package eu.mctraveler.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * The three flags a region owner turns *on* to take something away (spec User
 * Story 36): no fall damage inside, no strangers at the buttons, no strangers
 * at the doors.
 *
 * All three were accepted and enforced nowhere in the Portal (inventory §2.8,
 * deviation 7). The two "public" ones restrict people who are not members —
 * where "member" is the same resident-or-PUBLIC question every other refusal
 * asks — so with the flag off, the door and the lever stay open to everyone,
 * which is what leaving them unguarded in ticket 14 was for.
 */
class RegionFlagGameTest {

    // ---- DISABLE_PLAYER_FALL_DAMAGE ----

    @GameTest
    fun theFlagCatchesAPlayerWhoFallsInside(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15FallA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_PLAYER_FALL_DAMAGE")
        alice.standAt(helper, 2.0, 2.0, 2.0)

        alice.takesFallDamage(helper)

        helper.assertValueEqual(alice.health, alice.maxHealth, "the health of a resident who landed hard")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aFallStillHurtsWithoutTheFlag(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15DropA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.standAt(helper, 2.0, 2.0, 2.0)

        alice.takesFallDamage(helper)

        helper.assertTrue(alice.health < alice.maxHealth, "a fall in an ordinary region did no damage")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun theFlagCatchesStrangersToo(helper: GameTestHelper) {
        // The flag is about the ground, not about who is standing on it.
        val alice = MessageCapturingPlayer.join(helper, "T15SoftA")
        val bob = MessageCapturingPlayer.join(helper, "T15SoftB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_PLAYER_FALL_DAMAGE")
        bob.standAt(helper, 2.0, 2.0, 2.0)

        bob.takesFallDamage(helper)

        helper.assertValueEqual(bob.health, bob.maxHealth, "the health of a stranger who landed hard")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun onlyTheFallIsForgiven(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15HurtA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_PLAYER_FALL_DAMAGE")
        alice.standAt(helper, 2.0, 2.0, 2.0)

        alice.hurtServer(helper.level, helper.level.damageSources().magic(), FALL_DAMAGE)

        helper.assertTrue(alice.health < alice.maxHealth, "the flag shrugged off damage that was not a fall")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun togglingDisablePlayerFallDamageTakesEffectAtOnce(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15SoftLive")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.standAt(helper, 2.0, 2.0, 2.0)
        alice.takesFallDamage(helper)
        helper.assertTrue(alice.health < alice.maxHealth, "precondition: the fall did no damage")
        alice.health = alice.maxHealth

        alice.runCommand("rg flag DISABLE_PLAYER_FALL_DAMAGE")

        alice.takesFallDamage(helper)
        helper.assertValueEqual(alice.health, alice.maxHealth, "the health after the flag went on")
        alice.leave()
        helper.succeed()
    }

    // ---- DISABLE_PUBLIC_REDSTONE_TRIGGERS ----

    @GameTest
    fun theFlagKeepsStrangersOffTheLever(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15LevA")
        val bob = MessageCapturingPlayer.join(helper, "T15LevB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_PUBLIC_REDSTONE_TRIGGERS")
        helper.mount(Blocks.LEVER)
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.rightClicks(helper, TRIGGER_AT)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.POWERED, false)
        helper.assertValueEqual(bob.messages.last(), protectedBy("T15LevA's Place"), "the lever refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentStillFlipsTheirOwnLever(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15LevOk")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_PUBLIC_REDSTONE_TRIGGERS")
        helper.mount(Blocks.LEVER)
        alice.standAt(helper, 2.0, 2.0, 1.0)

        alice.rightClicks(helper, TRIGGER_AT)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.POWERED, true)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun withoutTheFlagAnyoneMayFlipTheLever(helper: GameTestHelper) {
        // What ticket 14 deliberately left open, so this flag has something to close.
        val alice = MessageCapturingPlayer.join(helper, "T15LevFree")
        val bob = MessageCapturingPlayer.join(helper, "T15LevFreeB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.mount(Blocks.LEVER)
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.rightClicks(helper, TRIGGER_AT)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.POWERED, true)
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun theFlagKeepsStrangersOffTheButton(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15BtnA")
        val bob = MessageCapturingPlayer.join(helper, "T15BtnB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_PUBLIC_REDSTONE_TRIGGERS")
        helper.mount(Blocks.STONE_BUTTON)
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.rightClicks(helper, TRIGGER_AT)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.POWERED, false)
        helper.assertTrue(bob.wasRefusedBy("T15BtnA's Place"), "no button refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun theFlagKeepsStrangersOffThePressurePlate(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15PlateA")
        val bob = MessageCapturingPlayer.join(helper, "T15PlateB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_PUBLIC_REDSTONE_TRIGGERS")
        helper.mount(Blocks.OAK_PRESSURE_PLATE)

        bob.stepsOnThePlate(helper)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.POWERED, false)
        // Ambient, and repeated every tick a foot is on it: the plate is silent.
        helper.assertFalse(bob.wasRefusedBy("T15PlateA's Place"), "the plate answered back")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentStillStandsOnTheirOwnPressurePlate(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15PlateOk")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_PUBLIC_REDSTONE_TRIGGERS")
        helper.mount(Blocks.OAK_PRESSURE_PLATE)

        alice.stepsOnThePlate(helper)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.POWERED, true)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun togglingDisablePublicRedstoneTriggersTakesEffectAtOnce(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15TrigLive")
        val bob = MessageCapturingPlayer.join(helper, "T15TrigLiveB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        helper.mount(Blocks.LEVER)
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.rightClicks(helper, TRIGGER_AT)
        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.POWERED, true)

        alice.runCommand("rg flag DISABLE_PUBLIC_REDSTONE_TRIGGERS")

        bob.rightClicks(helper, TRIGGER_AT)
        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.POWERED, true)
        helper.assertTrue(bob.wasRefusedBy("T15TrigLive's Place"), "no refusal once the flag went on")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    // ---- DISABLE_GATES ----

    @GameTest
    fun theFlagKeepsStrangersOutOfTheDoor(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15DoorA")
        val bob = MessageCapturingPlayer.join(helper, "T15DoorB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_GATES")
        helper.hangADoor()
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.rightClicks(helper, TRIGGER_AT)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.OPEN, false)
        helper.assertValueEqual(bob.messages.last(), protectedBy("T15DoorA's Place"), "the door refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentStillOpensTheirOwnDoor(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15DoorOk")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_GATES")
        helper.hangADoor()
        alice.standAt(helper, 2.0, 2.0, 1.0)

        alice.rightClicks(helper, TRIGGER_AT)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.OPEN, true)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun withoutTheFlagAnyoneMayOpenTheDoor(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15DoorFree")
        val bob = MessageCapturingPlayer.join(helper, "T15DoorFreeB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.hangADoor()
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.rightClicks(helper, TRIGGER_AT)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.OPEN, true)
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun theFlagKeepsStrangersOutOfTheFenceGate(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15GateA")
        val bob = MessageCapturingPlayer.join(helper, "T15GateB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_GATES")
        helper.setBlock(TRIGGER_AT, Blocks.OAK_FENCE_GATE)
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.rightClicks(helper, TRIGGER_AT)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.OPEN, false)
        helper.assertTrue(bob.wasRefusedBy("T15GateA's Place"), "no fence-gate refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun theFlagKeepsStrangersOutOfTheTrapdoor(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15TrapA")
        val bob = MessageCapturingPlayer.join(helper, "T15TrapB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_GATES")
        helper.setBlock(TRIGGER_AT, Blocks.OAK_TRAPDOOR)
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.rightClicks(helper, TRIGGER_AT)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.OPEN, false)
        helper.assertTrue(bob.wasRefusedBy("T15TrapA's Place"), "no trapdoor refusal")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun disableGatesLeavesTheLeverAlone(helper: GameTestHelper) {
        // The two flags are separate switches, and each closes only its own door.
        val alice = MessageCapturingPlayer.join(helper, "T15MixA")
        val bob = MessageCapturingPlayer.join(helper, "T15MixB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag DISABLE_GATES")
        helper.mount(Blocks.LEVER)
        bob.standAt(helper, 2.0, 2.0, 1.0)

        bob.rightClicks(helper, TRIGGER_AT)

        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.POWERED, true)
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun togglingDisableGatesTakesEffectAtOnce(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15GateLive")
        val bob = MessageCapturingPlayer.join(helper, "T15GateLiveB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        helper.hangADoor()
        bob.standAt(helper, 2.0, 2.0, 1.0)
        bob.rightClicks(helper, TRIGGER_AT)
        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.OPEN, true)

        alice.runCommand("rg flag DISABLE_GATES")

        bob.rightClicks(helper, TRIGGER_AT)
        helper.assertBlockProperty(TRIGGER_AT, BlockStateProperties.OPEN, true)
        helper.assertTrue(bob.wasRefusedBy("T15GateLive's Place"), "no refusal once the flag went on")
        alice.leave()
        bob.leave()
        helper.succeed()
    }
}

/** Where every lever, button, plate, door and gate in this file stands. */
private val TRIGGER_AT = BlockPos(2, 2, 2)

/** The block they all stand on. */
private val TRIGGER_FLOOR_AT = BlockPos(2, 1, 2)

/** More than a fall from a roof, less than a life. */
private const val FALL_DAMAGE = 6.0f

/** Puts a floor-mounted [block] (a lever, a button, a plate) at [TRIGGER_AT]. */
private fun GameTestHelper.mount(block: Block) {
    setBlock(TRIGGER_FLOOR_AT, Blocks.STONE)
    var state = block.defaultBlockState()
    if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) {
        state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
    }
    setBlock(TRIGGER_AT, state)
}

/** Hangs both halves of a door at [TRIGGER_AT], on something that will hold it. */
private fun GameTestHelper.hangADoor() {
    setBlock(TRIGGER_FLOOR_AT, Blocks.STONE)
    val door = Blocks.OAK_DOOR.defaultBlockState()
    setBlock(TRIGGER_AT, door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER))
    setBlock(TRIGGER_AT.above(), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER))
}

/** Right-clicks [at] empty-handed, the way a player opens a door or flips a lever. */
private fun MessageCapturingPlayer.rightClicks(helper: GameTestHelper, at: BlockPos) {
    val target = helper.absolutePos(at)
    val hit = BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false)
    gameMode.useItemOn(this, level(), mainHandItem, InteractionHand.MAIN_HAND, hit)
}

/** Stands on the plate at [TRIGGER_AT] and lets it notice, the way walking on does. */
private fun MessageCapturingPlayer.stepsOnThePlate(helper: GameTestHelper) {
    standAt(helper, 2.5, 2.0, 2.5)
    val plate = helper.absolutePos(TRIGGER_AT)
    helper.level.getBlockState(plate).entityInside(level(), plate, this, InsideBlockEffectApplier.NOOP, false)
}

/** Takes the damage a bad landing does. */
private fun MessageCapturingPlayer.takesFallDamage(helper: GameTestHelper) {
    invulnerableTime = 0
    hurtServer(helper.level, helper.level.damageSources().fall(), FALL_DAMAGE)
}
