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
 *    it, so a plain shard (or the wrong tier) in the centre yields nothing; and
 *  - a crystal is never an ingredient in anyone else's recipe, so a grid holding
 *    one yields nothing unless the result is itself a crystal.
 *
 * Observably identical to Nucleus: the wrong grid simply shows no result.
 */
object CrystalCrafting {

    /**
     * Whether the result a recipe computed for [grid] must be suppressed.
     * Pure: [grid] is the crafting grid's stacks in slot order and [result] what
     * vanilla would show for them.
     */
    fun blocks(grid: List<ItemStack>, result: ItemStack): Boolean {
        if (result.isEmpty) return false // nothing to suppress
        val crystalsInGrid = grid.filter(CrystalItem::isCrystal)
        // Someone else's recipe: a crystal is not an ingredient, it is the point.
        if (!CrystalItem.isCrystal(result)) return crystalsInGrid.isNotEmpty()
        val tier = CrystalItem.tierOf(result)
        if (tier <= CrystalItem.MIN_TIER) return false // tier 1 is crafted from an Eye of Ender
        // An upgrade must consume exactly the tier below: no skipping, no
        // re-crafting a tier from itself, and no plain shard standing in.
        return crystalsInGrid.none { CrystalItem.tierOf(it) == tier - 1 }
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
        if (!blocks(grid, resultSlots.getItem(RESULT_SLOT))) return
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
