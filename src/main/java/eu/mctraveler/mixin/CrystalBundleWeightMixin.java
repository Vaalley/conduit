package eu.mctraveler.mixin;

import com.mojang.serialization.DataResult;
import eu.mctraveler.crystal.CrystalItem;
import org.apache.commons.lang3.math.Fraction;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.component.BundleContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A crystal fits inside a bundle the way any ordinary item from a 64-stack
 * does, not the way a genuinely unstackable item (a shield, an elytra) does.
 *
 * <p>{@link BundleContents}'s per-item weight is {@code 1 / getMaxStackSize()}
 * — the same number that decides how many fit in an inventory slot decides
 * how much of a bundle's capacity one takes up. A crystal's
 * {@code DataComponents.MAX_STACK_SIZE} is deliberately pinned to 1
 * ({@link CrystalItem#of}'s doc comment; spec User Story 22) so that
 * identical-looking crystals never merge into a stack a player could misread
 * as one big charge — but that same pin would otherwise make a single
 * crystal fill an entire bundle by itself. This overrides the weight
 * calculation specifically for a crystal back to what an Echo Shard's real
 * stack size (64) would give it, leaving the inventory-slot pin untouched.
 */
@Mixin(BundleContents.class)
public abstract class CrystalBundleWeightMixin {

    /** An Echo Shard's real stack size — what a crystal's bundle weight is judged against instead of its pinned 1. */
    private static final int ECHO_SHARD_STACK_SIZE = 64;

    @Inject(
        method = "getWeight(Lnet/minecraft/world/item/ItemInstance;)Lcom/mojang/serialization/DataResult;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void mctraveler$crystalWeighsLikeAStackedItem(
        ItemInstance instance,
        CallbackInfoReturnable<DataResult<Fraction>> cir
    ) {
        if (CrystalItem.isCrystal(instance)) {
            cir.setReturnValue(DataResult.success(Fraction.getFraction(1, ECHO_SHARD_STACK_SIZE)));
        }
    }
}
