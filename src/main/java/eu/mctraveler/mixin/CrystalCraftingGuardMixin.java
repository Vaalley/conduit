package eu.mctraveler.mixin;

import eu.mctraveler.crystal.CrystalCrafting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The crystal crafting guard (spec User Stories 20-21, deviation 7).
 *
 * <p>{@code slotChangedCraftingGrid} is where every crafting grid — the table's
 * 3x3 and the player inventory's 2x2, which both route here — decides what it
 * currently crafts. Injecting at the tail lets vanilla decide first and leaves
 * this hook one job: take a result away that a datapack recipe could not have
 * refused, because ingredients cannot see components.
 *
 * <p>Vanilla has already told the client about the result by this point, so
 * {@link CrystalCrafting#guard} sends a fresh set-slot for the emptied result
 * rather than editing a packet in flight. The extra packet only happens with a
 * crystal in the grid.
 */
@Mixin(CraftingMenu.class)
public abstract class CrystalCraftingGuardMixin {

    @Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"))
    private static void mctraveler$guardCrystalCrafting(
            AbstractContainerMenu menu,
            ServerLevel level,
            Player player,
            CraftingContainer container,
            ResultContainer resultSlots,
            RecipeHolder<CraftingRecipe> recipeHint,
            CallbackInfo ci) {
        CrystalCrafting.guard(menu, player, container, resultSlots);
    }
}
