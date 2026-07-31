package eu.mctraveler.gametest

import eu.mctraveler.crystal.CrystalItem
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.CraftingMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Crafting the Teleportation Crystal (spec User Stories 20-21).
 *
 * Seam: a real [CraftingMenu] over the running server's recipe manager — the
 * same code path a player at a crafting table drives, so what these tests read
 * out of the result slot is what a player would see in it. The datapack recipes
 * and the component-aware guard (deviation 7) are both under test at once,
 * which is the point: neither is correct without the other.
 */
class CrystalCraftingGameTest {

    @GameTest
    fun anEyeOfEnderCraftsATierOneCrystal(helper: GameTestHelper) {
        val bench = CraftingBench.open(helper, "CrystalSmith1")
        try {
            bench.put(0, ItemStack(Items.ENDER_EYE))
            bench.assertCrafts(helper, 1)
            helper.succeed()
        } finally {
            bench.close()
        }
    }

    @GameTest
    fun amethystAroundATierOneCrystalCraftsATierTwo(helper: GameTestHelper) {
        val bench = CraftingBench.open(helper, "CrystalSmith2")
        try {
            bench.plus(centre = CrystalItem.of(1), arm = ItemStack(Items.AMETHYST_SHARD))
            bench.assertCrafts(helper, 2)
            helper.succeed()
        } finally {
            bench.close()
        }
    }

    @GameTest
    fun echoShardsAroundATierTwoCrystalCraftATierThree(helper: GameTestHelper) {
        val bench = CraftingBench.open(helper, "CrystalSmith3")
        try {
            bench.plus(centre = CrystalItem.of(2), arm = ItemStack(Items.ECHO_SHARD))
            bench.assertCrafts(helper, 3)
            helper.succeed()
        } finally {
            bench.close()
        }
    }

    @GameTest
    fun aPlainEchoShardInTheCentreCraftsNothing(helper: GameTestHelper) {
        val bench = CraftingBench.open(helper, "CrystalCounterfeiter")
        try {
            bench.plus(centre = ItemStack(Items.ECHO_SHARD), arm = ItemStack(Items.AMETHYST_SHARD))
            helper.assertTrue(
                bench.result().isEmpty,
                "a plain echo shard must not craft a tier 2, found ${bench.result()}",
            )
            bench.clear()
            bench.plus(centre = ItemStack(Items.ECHO_SHARD), arm = ItemStack(Items.ECHO_SHARD))
            helper.assertTrue(
                bench.result().isEmpty,
                "a plain echo shard must not craft a tier 3, found ${bench.result()}",
            )
            helper.succeed()
        } finally {
            bench.close()
        }
    }

    @GameTest
    fun aCrystalIsNeverAnIngredientInSomeoneElsesRecipe(helper: GameTestHelper) {
        val bench = CraftingBench.open(helper, "CrystalWaster")
        try {
            // The recovery compass: a compass ringed by 8 echo shards. Proven
            // first with ordinary shards, so the empty result below can only be
            // the guard's doing and not a mis-specified grid.
            for (slot in 0..8) bench.put(slot, ItemStack(Items.ECHO_SHARD))
            bench.put(4, ItemStack(Items.COMPASS))
            helper.assertTrue(
                bench.result().`is`(Items.RECOVERY_COMPASS),
                "8 echo shards and a compass should craft a recovery compass, found ${bench.result()}",
            )
            // Now one of those shards is a crystal.
            bench.put(0, CrystalItem.of(1))
            helper.assertTrue(
                bench.result().isEmpty,
                "a crystal in the grid must kill a foreign recipe, found ${bench.result()}",
            )
            helper.succeed()
        } finally {
            bench.close()
        }
    }
}

/**
 * A crafting table opened for a headless player: the 3x3 grid as slot indices
 * 0-8 and the result the server currently computes for it.
 */
internal class CraftingBench private constructor(
    private val player: MessageCapturingPlayer,
    private val menu: CraftingMenu,
) {
    /** Puts [stack] in grid slot [slot] (0-8, row-major), recomputing the result. */
    fun put(slot: Int, stack: ItemStack) {
        menu.getSlot(GRID_SLOT_START + slot).set(stack)
    }

    /** Lays out the crystal upgrade pattern: arms at the edge midpoints, [centre] in the middle. */
    fun plus(centre: ItemStack, arm: ItemStack) {
        for (slot in listOf(1, 3, 5, 7)) put(slot, arm.copy())
        put(4, centre)
    }

    fun clear() {
        for (slot in 0..8) put(slot, ItemStack.EMPTY)
    }

    /** What the grid currently crafts. */
    fun result(): ItemStack = menu.getSlot(RESULT_SLOT).item

    /** Asserts the grid crafts a crystal of [tier], identical to the one the mod builds. */
    fun assertCrafts(helper: GameTestHelper, tier: Int) {
        val crafted = result()
        helper.assertTrue(
            CrystalItem.isCrystal(crafted),
            "expected a tier $tier crystal, found $crafted",
        )
        helper.assertValueEqual(CrystalItem.tierOf(crafted), tier, "the crafted crystal's tier")
        helper.assertTrue(
            ItemStack.matches(crafted, CrystalItem.of(tier)),
            "the crafted tier $tier crystal differs from CrystalItem.of($tier)",
        )
    }

    fun close() {
        clear()
        player.leave()
    }

    companion object {
        private const val RESULT_SLOT = 0
        private const val GRID_SLOT_START = 1

        fun open(helper: GameTestHelper, name: String): CraftingBench {
            val player = MessageCapturingPlayer.join(helper, name)
            val menu = CraftingMenu(
                1,
                (player as Player).inventory,
                ContainerLevelAccess.create(helper.level, helper.absolutePos(BlockPos.ZERO)),
            )
            return CraftingBench(player, menu)
        }
    }
}
