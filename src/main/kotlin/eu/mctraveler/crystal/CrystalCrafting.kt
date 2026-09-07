package eu.mctraveler.crystal

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.CraftingContainer
import net.minecraft.world.inventory.ResultContainer
import net.minecraft.world.item.ItemStack

/**
 * The crafting guard (spec User Stories 20-21, deviation 7).
 *
 * Nucleus registered Bukkit recipes whose ingredients matched the crystal's NBT,
 * so a plain Echo Shard in the centre simply did not match. Datapack ingredients
 * are component-blind: our recipes match Echo Shards, crystal or not. This is
 * the part they cannot say —
 *
 *  - a crystal result above tier 1 must have been upgraded from the tier below
 *    it, sitting in the middle of the pattern, so a plain shard (or the wrong
 *    tier) there yields nothing; and
 *  - a crystal is never an ingredient in anyone else's recipe, so a grid holding
 *    one yields nothing unless the result is itself a crystal.
 *
 * Both rules are positional for a reason. The tier-3 recipe keys its arms *and*
 * its centre to `minecraft:echo_shard`, and a crystal is an echo shard, so a
 * crystal will happily satisfy an arm — which would let a plain shard in the
 * middle craft a tier 3 as long as a tier-2 crystal was thrown into a corner of
 * the plus. Nucleus could not be fooled that way: its ingredient was an exact
 * NBT match bound to the centre slot.
 *
 * Observably identical to Nucleus: the wrong grid simply shows no result.
 */
object CrystalCrafting {

    /**
     * Whether the result a recipe computed for [grid] must be suppressed.
     * Pure: [grid] is the crafting grid's stacks in slot order, [width] its row
     * length, and [result] what vanilla would show for them.
     */
    fun blocks(grid: List<ItemStack>, width: Int, result: ItemStack): Boolean {
        if (result.isEmpty) return false // nothing to suppress
        val crystalsInGrid = grid.filter(CrystalItem::isCrystal)
        // Someone else's recipe: a crystal is not an ingredient, it is the point.
        if (!CrystalItem.isCrystal(result)) return crystalsInGrid.isNotEmpty()
        val tier = CrystalItem.tierOf(result)
        if (tier <= CrystalItem.MIN_TIER) return false // tier 1 is crafted from an Eye of Ender
        // An upgrade consumes exactly one crystal, in the middle, one tier down:
        // no skipping a tier, no re-crafting a tier from itself, no plain shard
        // standing in, and no second crystal spent as if it were a plain shard.
        if (crystalsInGrid.size != 1) return true
        val middle = grid.getOrNull(middleIndex(grid.size, width) ?: return true) ?: return true
        return !CrystalItem.isCrystal(middle) || CrystalItem.tierOf(middle) != tier - 1
    }

    /**
     * The index of the grid's middle cell, or null for a grid that has no single
     * middle (an even side). The upgrade patterns are 3x3, so an even-sided grid
     * can never legitimately be crafting one.
     */
    private fun middleIndex(size: Int, width: Int): Int? {
        if (width <= 0 || size % width != 0) return null
        val height = size / width
        if (width % 2 == 0 || height % 2 == 0) return null
        return (height / 2) * width + width / 2
    }

    /**
     * Applies [blocks] to a crafting menu that has just recomputed its result,
     * clearing the result slot (and telling the client) when the grid must not
     * craft. Called from [eu.mctraveler.mixin.CrystalCraftingGuardMixin] after
     * vanilla has filled the slot, so vanilla decides what a grid crafts and we
     * only ever take a result away.
     */
    @JvmStatic
    fun guard(
        menu: AbstractContainerMenu,
        player: Player,
        container: CraftingContainer,
        resultSlots: ResultContainer,
    ) {
        val grid = (0 until container.containerSize).map(container::getItem)
        if (!blocks(grid, container.width, resultSlots.getItem(RESULT_SLOT))) return
        resultSlots.setItem(RESULT_SLOT, ItemStack.EMPTY)
        menu.setRemoteSlot(RESULT_SLOT, ItemStack.EMPTY)
        if (player is ServerPlayer) {
            player.connection.send(
                ClientboundContainerSetSlotPacket(
                    menu.containerId,
                    menu.incrementStateId(),
                    RESULT_SLOT,
                    ItemStack.EMPTY,
                ),
            )
        }
    }

    /** Vanilla's result slot index, in every crafting menu. */
    private const val RESULT_SLOT = 0
}
