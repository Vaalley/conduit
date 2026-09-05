package eu.mctraveler.region

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ArmorStandItem
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BoneMealItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.EndCrystalItem
import net.minecraft.world.item.FireChargeItem
import net.minecraft.world.item.FlintAndSteelItem
import net.minecraft.world.item.HangingEntityItem
import net.minecraft.world.item.HoeItem
import net.minecraft.world.item.HoneycombItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.LeadItem
import net.minecraft.world.item.MobBucketItem
import net.minecraft.world.item.ShearsItem
import net.minecraft.world.item.ShovelItem
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.CartographyTableMenu
import net.minecraft.world.inventory.CraftingMenu
import net.minecraft.world.inventory.EnchantmentMenu
import net.minecraft.world.inventory.GrindstoneMenu
import net.minecraft.world.inventory.LoomMenu
import net.minecraft.world.inventory.SmithingMenu
import net.minecraft.world.inventory.StonecutterMenu
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.AbstractFurnaceBlock
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.BeaconBlock
import net.minecraft.world.level.block.BeehiveBlock
import net.minecraft.world.level.block.BellBlock
import net.minecraft.world.level.block.BrewingStandBlock
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.CartographyTableBlock
import net.minecraft.world.level.block.ChiseledBookShelfBlock
import net.minecraft.world.level.block.ComposterBlock
import net.minecraft.world.level.block.CrafterBlock
import net.minecraft.world.level.block.CraftingTableBlock
import net.minecraft.world.level.block.DaylightDetectorBlock
import net.minecraft.world.level.block.DecoratedPotBlock
import net.minecraft.world.level.block.DispenserBlock
import net.minecraft.world.level.block.DragonEggBlock
import net.minecraft.world.level.block.EnchantingTableBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.GrindstoneBlock
import net.minecraft.world.level.block.JukeboxBlock
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.LoomBlock
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.level.block.ShelfBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.SmithingTableBlock
import net.minecraft.world.level.block.StonecutterBlock
import net.minecraft.world.level.block.TntBlock
import net.minecraft.world.level.block.state.BlockState

/**
 * How a region judges a non-member right-clicking a block, or applying an item
 * to one (issue #40): by what the interaction would change, not by whether an
 * item happens to be in hand.
 *
 * This is the classification only. [RegionProtection] turns a class into an
 * allow-or-refuse verdict and sends the message.
 */
object RegionInteractables {

    /** What a non-member's right-click on a block is allowed to do. */
    enum class BlockUse {
        /** Workstations and read-only blocks — open for anyone, no message. */
        FREE,

        /**
         * A container whose GUI a non-member may open to look inside; every
         * slot click is still refused by `RegionContainerClickMixin`. The one
         * thing this class changes from [FREE] is nothing at this layer — both
         * pass — but it names the intent, and a later rule may want the
         * distinction.
         */
        CONTAINER_VIEW,

        /** Changes the region's contents — refused with the message. */
        REQUIRES_MEMBERSHIP,

        /** A campfire — cookable food may be placed, nothing else. */
        CAMPFIRE,
    }

    /**
     * The class [state]'s own right-click behaviour falls into. A block this
     * does not name is [BlockUse.FREE] here: the block itself gates nothing,
     * and any item applied to it is still judged by [isRegionModifyingItem].
     */
    fun classify(state: BlockState): BlockUse = when (state.block) {
        is CraftingTableBlock, is CartographyTableBlock, is SmithingTableBlock,
        is GrindstoneBlock, is LoomBlock, is EnchantingTableBlock,
        is StonecutterBlock, is BellBlock,
        -> BlockUse.FREE
        // Lodestone has no block class of its own; compass-linking is the
        // compass's own use-on, which changes the compass, not the region, and
        // so passes [isRegionModifyingItem] already. An empty-hand click on a
        // lodestone does nothing and falls through to the FREE default below.

        is AbstractFurnaceBlock, is DispenserBlock, is BrewingStandBlock,
        is BeaconBlock, is LecternBlock,
        -> BlockUse.CONTAINER_VIEW

        is CampfireBlock -> BlockUse.CAMPFIRE

        // A crafter holds the region's own recipe ingredients and its layout is
        // part of the build, so — unlike a furnace a stranger may glance into —
        // a non-member does not open it at all.
        is CrafterBlock,
        is ComposterBlock, is AbstractCauldronBlock, is ChiseledBookShelfBlock,
        is DecoratedPotBlock, is JukeboxBlock, is DaylightDetectorBlock,
        is NoteBlock, is AnvilBlock, is FlowerPotBlock, is BeehiveBlock,
        is ShelfBlock, is DragonEggBlock, is RespawnAnchorBlock, is SignBlock,
        // TnT: its own useItemOn returns SUCCESS for flint&steel / fire charge
        // and primes the charge, so Fabric's ItemEvents.USE_ON never fires for
        // it — refusing the block's own right-click is what keeps a non-member
        // from lighting it by hand.
        is TntBlock,
        -> BlockUse.REQUIRES_MEMBERSHIP

        else -> BlockUse.FREE
    }

    /**
     * Whether applying [stack] to a block would place or change something —
     * the item uses a region refuses a non-member. Everything else (a sword, a
     * spyglass, a map, a stick, an empty hand) does nothing to a block and is
     * let through with no message.
     *
     * `ItemEvents.USE_ON` fires only after the block declined the click, so a
     * tool that would do nothing to the block it hit (a hoe on stone) still
     * counts here — poking someone's land with a tool is a build intent, and
     * the classes that need to be silent (a workstation, a container to look
     * in) are settled by [classify] before the item is ever consulted.
     *
     * Bone meal is here for the by-item half of its rule: a non-member's bone
     * meal never works anywhere in a region, even where the target block would
     * accept it.
     */
    fun isRegionModifyingItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        return when (stack.item) {
            is BlockItem, is BucketItem, is MobBucketItem,
            is BoneMealItem, is FlintAndSteelItem, is FireChargeItem,
            is HoeItem, is ShovelItem, is AxeItem, is ShearsItem,
            is ArmorStandItem, is HangingEntityItem, is EndCrystalItem,
            is SpawnEggItem, is LeadItem, is HoneycombItem,
            -> true

            // A water potion converting dirt to mud is handled precisely by
            // RegionProtection.exemptUseChangesBlock, and an ender eye in an
            // end-portal frame the same way — neither is blanket-listed here,
            // because on any other block they do nothing.
            else -> false
        }
    }

    /**
     * Whether [stack] would extinguish, relight, or otherwise change a campfire
     * rather than cook on it. A cookable food item is not one of these.
     */
    fun changesCampfire(stack: ItemStack): Boolean {
        if (stack.isEmpty || stack.has(DataComponents.FOOD)) return false
        return when (stack.item) {
            is ShovelItem, is FlintAndSteelItem, is FireChargeItem -> true
            else -> isWaterPotion(stack) || stack.`is`(Items.POTION)
        }
    }

    /** Whether food set on a campfire to cook — the one campfire use a non-member keeps. */
    fun isCampfireFood(stack: ItemStack): Boolean =
        !stack.isEmpty && stack.has(DataComponents.FOOD)

    /**
     * Whether [menu] is a workstation whose contents belong to the player using
     * it, not to the region it stands in — a crafting table, grindstone,
     * smithing table, loom, stonecutter, cartography table or enchanting table.
     * A non-member may work one of these to the full, so the container-session
     * protection ([RegionProtection.allowsContainerUse]) sits it out. A chest,
     * a furnace, a crafter — anything that holds a region's own items — is not
     * one of these.
     */
    fun isWorkstationMenu(menu: AbstractContainerMenu): Boolean =
        menu is CraftingMenu ||
            menu is GrindstoneMenu ||
            menu is SmithingMenu ||
            menu is LoomMenu ||
            menu is StonecutterMenu ||
            menu is CartographyTableMenu ||
            menu is EnchantmentMenu

    private fun isWaterPotion(stack: ItemStack): Boolean {
        if (!stack.`is`(Items.POTION) && !stack.`is`(Items.SPLASH_POTION) && !stack.`is`(Items.LINGERING_POTION)) {
            return false
        }
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).`is`(Potions.WATER)
    }
}
