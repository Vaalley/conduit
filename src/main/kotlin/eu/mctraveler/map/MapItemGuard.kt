package eu.mctraveler.map

import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack

object MapItemGuard {
    /**
     * Checks if [stack] is a treasure map or explorer map by inspecting whether it carries
     * map decorations (such as structure X marks) or post-processing components.
     */
    fun isTreasureOrExplorerMap(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val decorations = stack.get(DataComponents.MAP_DECORATIONS)
        val hasDecorations = decorations != null && !decorations.decorations.isEmpty()
        return hasDecorations || stack.has(DataComponents.MAP_POST_PROCESSING)
    }

    /**
     * Intercepts right-clicking with treasure/explorer map items to prevent vanilla item usage
     * (e.g. EmptyMapItem.use) from replacing or resetting the treasure map into a regular map.
     */
    fun register() {
        UseItemCallback.EVENT.register { player, _, hand ->
            val stack = player.getItemInHand(hand)
            if (isTreasureOrExplorerMap(stack)) {
                InteractionResult.FAIL
            } else {
                InteractionResult.PASS
            }
        }
    }
}
