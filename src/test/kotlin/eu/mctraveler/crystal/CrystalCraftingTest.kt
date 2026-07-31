package eu.mctraveler.crystal

import eu.mctraveler.MinecraftTestBootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The crafting guard (spec User Stories 20-21, deviation 7).
 *
 * Datapack ingredients are component-blind, so the three crystal recipes match
 * on plain Echo Shards and Amethyst Shards alone. This is the rule the recipes
 * cannot state: what a *crystal* in the grid means. Two things — the centre of a
 * tier-2/3 recipe must be the tier below it, and a crystal is never an ingredient
 * in anything else.
 */
class CrystalCraftingTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() = MinecraftTestBootstrap.ensure()
    }

    /** The plus pattern the tier-2 and tier-3 recipes share, centre first. */
    private fun plus(centre: ItemStack, arm: ItemStack) = listOf(
        ItemStack.EMPTY, arm, ItemStack.EMPTY,
        arm, centre, arm,
        ItemStack.EMPTY, arm, ItemStack.EMPTY,
    )

    private val amethyst get() = ItemStack(Items.AMETHYST_SHARD)
    private val echoShard get() = ItemStack(Items.ECHO_SHARD)

    @Test
    fun `an eye of ender crafts a tier-1 crystal`() {
        assertFalse(CrystalCrafting.blocks(listOf(ItemStack(Items.ENDER_EYE)), CrystalItem.of(1)))
    }

    @Test
    fun `amethyst around a tier-1 crystal crafts a tier-2`() {
        assertFalse(CrystalCrafting.blocks(plus(CrystalItem.of(1), amethyst), CrystalItem.of(2)))
    }

    @Test
    fun `echo shards around a tier-2 crystal craft a tier-3`() {
        assertFalse(CrystalCrafting.blocks(plus(CrystalItem.of(2), echoShard), CrystalItem.of(3)))
    }

    @Test
    fun `a plain echo shard in the centre crafts nothing`() {
        assertTrue(CrystalCrafting.blocks(plus(echoShard, amethyst), CrystalItem.of(2)))
        assertTrue(CrystalCrafting.blocks(plus(echoShard, echoShard), CrystalItem.of(3)))
    }

    @Test
    fun `a crystal of the wrong tier in the centre crafts nothing`() {
        // Tier 2 is only reachable from tier 1, tier 3 only from tier 2 — a
        // crystal may never skip or repeat a step.
        assertTrue(CrystalCrafting.blocks(plus(CrystalItem.of(2), amethyst), CrystalItem.of(2)))
        assertTrue(CrystalCrafting.blocks(plus(CrystalItem.of(3), amethyst), CrystalItem.of(2)))
        assertTrue(CrystalCrafting.blocks(plus(CrystalItem.of(1), echoShard), CrystalItem.of(3)))
        assertTrue(CrystalCrafting.blocks(plus(CrystalItem.of(3), echoShard), CrystalItem.of(3)))
    }

    @Test
    fun `a crystal is never an ingredient in someone else's recipe`() {
        // The recovery compass: 8 echo shards around a compass — and one of the
        // shards happens to be a crystal.
        val grid = MutableList(9) { echoShard }
        grid[4] = ItemStack(Items.COMPASS)
        grid[0] = CrystalItem.of(1)
        assertTrue(CrystalCrafting.blocks(grid, ItemStack(Items.RECOVERY_COMPASS)))
    }

    @Test
    fun `an ordinary recipe with no crystal in it is left alone`() {
        val grid = MutableList(9) { echoShard }
        grid[4] = ItemStack(Items.COMPASS)
        assertFalse(CrystalCrafting.blocks(grid, ItemStack(Items.RECOVERY_COMPASS)))
    }

    @Test
    fun `a grid that already crafts nothing is left alone`() {
        assertFalse(CrystalCrafting.blocks(plus(CrystalItem.of(1), amethyst), ItemStack.EMPTY))
    }
}
