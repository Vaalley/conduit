package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Region protection for buckets. A bucket does not act on the block a player is
 * looking at through {@code useItemOn} — it ray-traces from the player's eyes
 * and empties or fills at whatever it strikes — so {@link RegionProtection}'s
 * item hooks, which read the player's feet, never see where the water lands. A
 * stranger standing just outside a region could pour water anywhere inside it,
 * or bail a pool out of it (issue #49).
 *
 * <p>Both ends are guarded at the seam the resolved position is known:
 * {@code emptyContents} is called with the final placement position, and the
 * {@code BucketPickup.pickupBlock} call in {@code use} with the source it is
 * about to remove. Dispensers pass a non-player {@code entity} and fall through
 * untouched — a dispenser firing into a region is {@link RegionEnvironment}'s
 * concern, not this one.
 */
@Mixin(BucketItem.class)
public abstract class RegionBucketMixin {

    @Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
    private void mctraveler$protectBucketEmpty(
            LivingEntity entity,
            Level level,
            BlockPos pos,
            BlockHitResult hit,
            CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof ServerPlayer player && !Hooks.allowsBlockChange(player, level, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(
            method = "use",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack mctraveler$protectBucketFill(
            BucketPickup pickup,
            LivingEntity entity,
            LevelAccessor level,
            BlockPos pos,
            BlockState state) {
        if (entity instanceof ServerPlayer player
                && level instanceof Level realLevel
                && !Hooks.allowsBlockChange(player, realLevel, pos)) {
            return ItemStack.EMPTY;
        }
        return pickup.pickupBlock(entity, level, pos, state);
    }
}
