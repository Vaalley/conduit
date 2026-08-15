package eu.mctraveler.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3

/**
 * What a region stops the *world* doing (spec User Story 35): explosions, fire,
 * pistons reaching in from outside, and creatures that rearrange blocks. None of
 * these has a player to refuse, so all of them are silent — the only thing to
 * assert is whether the blocks are still there.
 *
 * Every test builds its region through the real commands and keeps every
 * coordinate within a few blocks of its own structure — the gametest batch lays
 * structures out roughly 15 blocks apart.
 */
class RegionEnvironmentGameTest {

    // ---- explosions ----

    @GameTest
    fun anExplosionSparesRegionBlocks(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15BoomA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(TARGET_AT, Blocks.DIRT)

        helper.explodeAt(TARGET_AT)

        helper.assertBlockPresent(Blocks.DIRT, TARGET_AT)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun explosionsOutsideEveryRegionStillDestroy(helper: GameTestHelper) {
        helper.setBlock(TARGET_AT, Blocks.DIRT)

        helper.explodeAt(TARGET_AT)

        helper.assertBlockNotPresent(Blocks.DIRT, TARGET_AT)
        helper.succeed()
    }

    @GameTest
    fun enableExplosionsOptsTheRegionBackIn(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15TntA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        alice.runCommand("rg flag ENABLE_EXPLOSIONS")
        helper.setBlock(TARGET_AT, Blocks.DIRT)

        helper.explodeAt(TARGET_AT)

        helper.assertBlockNotPresent(Blocks.DIRT, TARGET_AT)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun togglingEnableExplosionsTakesEffectAtOnce(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15LiveA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        helper.setBlock(TARGET_AT, Blocks.DIRT)
        helper.explodeAt(TARGET_AT)
        helper.assertBlockPresent(Blocks.DIRT, TARGET_AT)

        alice.runCommand("rg flag ENABLE_EXPLOSIONS")

        helper.explodeAt(TARGET_AT)
        helper.assertBlockNotPresent(Blocks.DIRT, TARGET_AT)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun anExplosionStillHurtsInsideAProtectedRegion(helper: GameTestHelper) {
        // Only the region's *blocks* are shielded: the blast itself is vanilla.
        val alice = MessageCapturingPlayer.join(helper, "T15BlastA")
        val bob = MessageCapturingPlayer.join(helper, "T15BlastB")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        bob.standAt(helper, 2.0, 2.0, 2.0)

        helper.explodeAt(TARGET_AT)

        helper.assertTrue(bob.health < bob.maxHealth, "a protected region absorbed the blast damage too")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    // ---- fire ----

    @GameTest
    fun fireDoesNotBurnRegionBlocks(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15BurnA")
        createRegion(helper, alice, 2.0 to 0.0, 4.0 to 4.0)
        helper.lightAFireOutside()
        helper.setBlock(NEXT_TO_FIRE, Blocks.OAK_PLANKS)

        helper.letTheFireBurn()

        helper.assertBlockPresent(Blocks.OAK_PLANKS, NEXT_TO_FIRE)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun fireOutsideEveryRegionStillBurns(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15AshA")
        alice.standAt(helper, 4.0, 1.0, 4.0)
        helper.lightAFireOutside()
        helper.setBlock(NEXT_TO_FIRE, Blocks.OAK_PLANKS)

        helper.letTheFireBurn()

        helper.assertBlockNotPresent(Blocks.OAK_PLANKS, NEXT_TO_FIRE)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun fireDoesNotSpreadIntoARegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15SpreadA")
        createRegion(helper, alice, 2.0 to 0.0, 4.0 to 4.0)
        helper.lightAFireOutside()
        helper.setBlock(FUEL_AT, Blocks.HAY_BLOCK)

        helper.letTheFireBurn()

        helper.assertBlockNotPresent(Blocks.FIRE, NEXT_TO_FIRE)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun fireSpreadsOutsideEveryRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15SparkA")
        alice.standAt(helper, 4.0, 1.0, 4.0)
        helper.lightAFireOutside()
        helper.setBlock(FUEL_AT, Blocks.HAY_BLOCK)

        helper.letTheFireBurn()

        helper.assertBlockPresent(Blocks.FIRE, NEXT_TO_FIRE)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun togglingEnableFireDamageTakesEffectAtOnce(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15FlameA")
        createRegion(helper, alice, 2.0 to 0.0, 4.0 to 4.0)
        alice.makeAdmin()
        helper.lightAFireOutside()
        helper.setBlock(NEXT_TO_FIRE, Blocks.OAK_PLANKS)
        helper.letTheFireBurn()
        helper.assertBlockPresent(Blocks.OAK_PLANKS, NEXT_TO_FIRE)

        alice.runCommand("rg flag ENABLE_FIRE_DAMAGE")

        helper.letTheFireBurn()
        helper.assertBlockNotPresent(Blocks.OAK_PLANKS, NEXT_TO_FIRE)
        alice.leave()
        helper.succeed()
    }

    // ---- pistons ----

    @GameTest
    fun aPistonOutsideCannotPushIntoARegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15PushA")
        createRegion(helper, alice, 3.0 to 0.0, 5.0 to 4.0)
        helper.setBlock(PUSHED_AT, Blocks.DIRT)
        helper.armPiston(Blocks.PISTON)

        helper.powerThePiston()
        helper.runAfterDelay(PISTON_TICKS) {
            helper.assertBlockPresent(Blocks.DIRT, PUSHED_AT)
            helper.assertBlockNotPresent(Blocks.DIRT, PUSHED_TO)
            alice.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun aPistonOutsideEveryRegionStillPushes(helper: GameTestHelper) {
        helper.setBlock(PUSHED_AT, Blocks.DIRT)
        helper.armPiston(Blocks.PISTON)

        helper.powerThePiston()
        helper.runAfterDelay(PISTON_TICKS) {
            helper.assertBlockPresent(Blocks.DIRT, PUSHED_TO)
            helper.succeed()
        }
    }

    @GameTest
    fun aPistonInsideTheRegionPushesFreely(helper: GameTestHelper) {
        // The rule is about reaching *in*: a region's own redstone still works.
        val alice = MessageCapturingPlayer.join(helper, "T15OwnA")
        createRegion(helper, alice, 0.0 to 0.0, 5.0 to 4.0)
        helper.setBlock(PUSHED_AT, Blocks.DIRT)
        helper.armPiston(Blocks.PISTON)

        helper.powerThePiston()
        helper.runAfterDelay(PISTON_TICKS) {
            helper.assertBlockPresent(Blocks.DIRT, PUSHED_TO)
            alice.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun aPistonOutsideCannotPullBlocksOutOfARegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15PullA")
        createRegion(helper, alice, 3.0 to 0.0, 5.0 to 4.0)
        helper.setBlock(PUSHED_TO, Blocks.DIRT)
        helper.armPiston(Blocks.STICKY_PISTON)

        helper.powerThePiston()
        helper.runAfterDelay(PISTON_TICKS) {
            helper.unpowerThePiston()
            helper.runAfterDelay(PISTON_TICKS) {
                helper.assertBlockPresent(Blocks.DIRT, PUSHED_TO)
                // The arm still comes home: a refused pull is an empty pull,
                // not a piston stuck half-extended.
                helper.assertBlockNotPresent(Blocks.PISTON_HEAD, PUSHED_AT)
                alice.leave()
                helper.succeed()
            }
        }
    }

    @GameTest
    fun aStickyPistonOutsideEveryRegionStillPulls(helper: GameTestHelper) {
        helper.setBlock(PUSHED_TO, Blocks.DIRT)
        helper.armPiston(Blocks.STICKY_PISTON)

        helper.powerThePiston()
        helper.runAfterDelay(PISTON_TICKS) {
            helper.unpowerThePiston()
            helper.runAfterDelay(PISTON_TICKS) {
                helper.assertBlockPresent(Blocks.DIRT, PUSHED_AT)
                helper.succeed()
            }
        }
    }

    // ---- mob griefing ----

    @GameTest
    fun aMobCannotSmashRegionBlocks(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15MobA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(TARGET_AT, Blocks.DIRT)
        val ravager = helper.spawnWithNoFreeWill(EntityTypes.RAVAGER, BlockPos(1, 2, 1))

        helper.level.destroyBlock(helper.absolutePos(TARGET_AT), true, ravager)

        helper.assertBlockPresent(Blocks.DIRT, TARGET_AT)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aMobSmashesBlocksOutsideEveryRegion(helper: GameTestHelper) {
        helper.setBlock(TARGET_AT, Blocks.DIRT)
        val ravager = helper.spawnWithNoFreeWill(EntityTypes.RAVAGER, BlockPos(1, 2, 1))

        helper.level.destroyBlock(helper.absolutePos(TARGET_AT), true, ravager)

        helper.assertBlockNotPresent(Blocks.DIRT, TARGET_AT)
        helper.succeed()
    }

    @GameTest
    fun theWorldsOwnBlockPhysicsStillRunInsideARegion(helper: GameTestHelper) {
        // Nothing is asking here — a block losing its support, a plant losing
        // its light. Only a creature is held off, so a region's own physics
        // must keep working or its owner could never break anything.
        val alice = MessageCapturingPlayer.join(helper, "T15PhysA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(TARGET_AT, Blocks.DIRT)

        helper.level.destroyBlock(helper.absolutePos(TARGET_AT), false)

        helper.assertBlockNotPresent(Blocks.DIRT, TARGET_AT)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aPlayersOwnSplashOfWaterStillWorksInTheirRegion(helper: GameTestHelper) {
        // A splash potion dousing a fire destroys the block as *itself*, not as
        // the player who threw it. A thrown thing has to count as its thrower,
        // or a resident could not put out a fire in their own region.
        val alice = MessageCapturingPlayer.join(helper, "T15SplashA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(TARGET_AT, Blocks.DIRT)
        val bottle = helper.spawn(EntityTypes.SPLASH_POTION, BlockPos(1, 2, 1))
        bottle.owner = alice

        helper.level.destroyBlock(helper.absolutePos(TARGET_AT), false, bottle)

        helper.assertBlockNotPresent(Blocks.DIRT, TARGET_AT)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aMobsThrownThingIsStillTheMob(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15WitchA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(TARGET_AT, Blocks.DIRT)
        val witch = helper.spawnWithNoFreeWill(EntityTypes.WITCH, BlockPos(1, 2, 1))
        val bottle = helper.spawn(EntityTypes.SPLASH_POTION, BlockPos(1, 2, 1))
        bottle.owner = witch

        helper.level.destroyBlock(helper.absolutePos(TARGET_AT), false, bottle)

        helper.assertBlockPresent(Blocks.DIRT, TARGET_AT)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun anEndermanCannotTakeARegionsBlocks(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15EndA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(ENDERMAN_REACH_AT, Blocks.GRASS_BLOCK)
        val enderman = helper.anIdleEnderman()

        enderman.runGoal(TAKE_BLOCK_GOAL, ENDERMAN_TRIES)

        helper.assertBlockPresent(Blocks.GRASS_BLOCK, ENDERMAN_REACH_AT)
        helper.assertTrue(enderman.carriedBlock == null, "the enderman walked off with a region block")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun anEndermanTakesBlocksOutsideEveryRegion(helper: GameTestHelper) {
        helper.setBlock(ENDERMAN_REACH_AT, Blocks.GRASS_BLOCK)
        val enderman = helper.anIdleEnderman()

        enderman.runGoal(TAKE_BLOCK_GOAL, ENDERMAN_TRIES)

        helper.assertBlockNotPresent(Blocks.GRASS_BLOCK, ENDERMAN_REACH_AT)
        helper.succeed()
    }

    @GameTest
    fun anEndermanCannotDropABlockInARegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T15DropA")
        createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
        helper.setBlock(ENDERMAN_SHELF_AT, Blocks.STONE)
        val enderman = helper.anIdleEnderman()
        enderman.carriedBlock = Blocks.GRASS_BLOCK.defaultBlockState()

        enderman.runGoal(LEAVE_BLOCK_GOAL, ENDERMAN_TRIES)

        helper.assertBlockNotPresent(Blocks.GRASS_BLOCK, ENDERMAN_DROP_AT)
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun anEndermanDropsBlocksOutsideEveryRegion(helper: GameTestHelper) {
        helper.setBlock(ENDERMAN_SHELF_AT, Blocks.STONE)
        val enderman = helper.anIdleEnderman()
        enderman.carriedBlock = Blocks.GRASS_BLOCK.defaultBlockState()

        enderman.runGoal(LEAVE_BLOCK_GOAL, ENDERMAN_TRIES)

        helper.assertBlockPresent(Blocks.GRASS_BLOCK, ENDERMAN_DROP_AT)
        helper.succeed()
    }
}

/** The block every environment test works on, inside the test region. */
private val TARGET_AT = BlockPos(2, 2, 2)

/** Where the fire tests burn, one block outside the region they threaten. */
private val FIRE_AT = BlockPos(1, 2, 2)

/** The bedrock the fire stands on, so it never eats its own floor. */
private val FIRE_FLOOR_AT = BlockPos(1, 1, 2)

/**
 * The fire's own fuel, outside the region. A fire with nothing burnable beside
 * it is not a valid fire location and gives up before it ever looks around, so
 * every fire test keeps this stocked (see [stokeTheFire]).
 */
private val OUTSIDE_FUEL_AT = BlockPos(0, 2, 2)

/** The first block inside the region the fire can reach: what it burns, or spreads into. */
private val NEXT_TO_FIRE = BlockPos(2, 2, 2)

/**
 * Fuel one block deeper in, so the fire has somewhere to spread *towards*.
 * Hay is the most ignitable solid block there is, which keeps the odds of the
 * spread landing inside one test's tick budget comfortably high.
 */
private val FUEL_AT = BlockPos(3, 2, 2)

/** The seed every driven block tick runs on, so a red test stays red. */
private const val TICK_SEED = 1337L

/** How many of its own ticks a driven fire gets — enough for its odds to land. */
private const val FIRE_TICKS = 600

/** Sets off a TNT-sized explosion centred on [at]. */
private fun GameTestHelper.explodeAt(at: BlockPos, power: Float = 4.0f) {
    val centre = Vec3.atCenterOf(absolutePos(at))
    level.explode(null, centre.x, centre.y, centre.z, power, Level.ExplosionInteraction.TNT)
}

/** Lights a fire on bedrock just outside the region, with its own fuel beside it. */
private fun GameTestHelper.lightAFireOutside() {
    setBlock(FIRE_FLOOR_AT, Blocks.BEDROCK)
    stokeTheFire()
}

/**
 * Runs the fire through its own scheduled ticks — the real spread-and-burn
 * engine, on a fixed seed rather than the ~35-tick wait between two of them.
 */
private fun GameTestHelper.letTheFireBurn(times: Int = FIRE_TICKS) {
    val fire = absolutePos(FIRE_AT)
    val random = RandomSource.create(TICK_SEED)
    repeat(times) {
        stokeTheFire()
        level.getBlockState(fire).tick(level, fire, random)
    }
}

/**
 * Keeps the fire outside the region alight and fed, so what a test measures is
 * how far the fire reaches rather than how long this particular flame lasted.
 */
private fun GameTestHelper.stokeTheFire() {
    if (!getBlockState(OUTSIDE_FUEL_AT).`is`(Blocks.HAY_BLOCK)) setBlock(OUTSIDE_FUEL_AT, Blocks.HAY_BLOCK)
    if (!getBlockState(FIRE_AT).`is`(Blocks.FIRE)) setBlock(FIRE_AT, Blocks.FIRE)
}

/** The piston, outside the region in every test but one, facing east into it. */
private val PISTON_AT = BlockPos(1, 2, 2)

/** What powers the piston — above it, never on its face. */
private val POWER_AT = BlockPos(1, 3, 2)

/** Where the pushed block starts, and where a pull would drop it: outside. */
private val PUSHED_AT = BlockPos(2, 2, 2)

/** Where a push would land, and where a pull would take it from: inside. */
private val PUSHED_TO = BlockPos(3, 2, 2)

/** Long enough for the block event, the two-tick slide, and the final tick. */
private const val PISTON_TICKS = 8L

/** Puts a piston at [PISTON_AT] facing the region. */
private fun GameTestHelper.armPiston(piston: Block) {
    setBlock(PISTON_AT, piston.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST))
}

private fun GameTestHelper.powerThePiston() = setBlock(POWER_AT, Blocks.REDSTONE_BLOCK)

private fun GameTestHelper.unpowerThePiston() = setBlock(POWER_AT, Blocks.AIR)

/** Where the test enderman stands, in the middle of the region. */
private val ENDERMAN_AT = BlockPos(2, 2, 2)

/** The one block an enderman standing at [ENDERMAN_AT] can pick up. */
private val ENDERMAN_REACH_AT = BlockPos(3, 2, 2)

/** The one shelf an enderman standing at [ENDERMAN_AT] can put a block down on. */
private val ENDERMAN_SHELF_AT = BlockPos(1, 1, 2)

/** Where a block put down on [ENDERMAN_SHELF_AT] lands. */
private val ENDERMAN_DROP_AT = BlockPos(1, 2, 2)

/** How many goal ticks an enderman gets — it aims at a random cell each time. */
private const val ENDERMAN_TRIES = 600

private const val TAKE_BLOCK_GOAL = "EndermanTakeBlockGoal"
private const val LEAVE_BLOCK_GOAL = "EndermanLeaveBlockGoal"

/** An enderman that will do nothing but what a test asks of it. */
private fun GameTestHelper.anIdleEnderman(): EnderMan =
    spawn(EntityTypes.ENDERMAN, ENDERMAN_AT).also { it.isNoAi = true }

/**
 * Runs one of a mob's own AI goals [times] over, instead of waiting for the mob
 * to pick it. Vanilla's mob goals are private inner classes with no other way
 * in, so they are found by the tail of their class name.
 */
private fun Mob.runGoal(named: String, times: Int) {
    val goal = getGoalSelector().availableGoals
        .map { it.goal }
        .firstOrNull { it.javaClass.name.endsWith(named) }
        ?: error("this ${type.description.string} has no $named")
    repeat(times) { goal.tick() }
}
