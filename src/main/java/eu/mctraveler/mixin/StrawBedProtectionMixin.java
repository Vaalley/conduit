package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import eu.mctraveler.region.RegionStrawBeds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.StrawBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A straw bed breaking itself is a real vanilla mechanic (a fresh one, not a
 * bug this mod introduces) — {@code destroyOnUse} fires on the click itself
 * (in a dimension whose environment attributes say beds don't work there at
 * all), {@code destroyOnLeave} when its sleeper gets up (a straw bed's own
 * single-use nature). Left alone, either lets a visitor destroy someone
 * else's straw bed inside their region exactly as if they owned it; this
 * closes that gap the way {@code RegionChorusFlowerMixin} closes chorus
 * flowers', by refusing the destroy rather than touching the mechanic.
 */
@Mixin(StrawBedBlock.class)
public abstract class StrawBedProtectionMixin {

    @Inject(method = "destroyOnUse", at = @At("HEAD"), cancellable = true)
    private void mctraveler$protectStrawBedOnUse(
        BlockState state,
        Level level,
        BlockPos pos,
        Player player,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (player instanceof ServerPlayer serverPlayer && !Hooks.allowsBlockChange(serverPlayer, level, pos)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "destroyOnLeave", at = @At("HEAD"), cancellable = true)
    private void mctraveler$protectStrawBedOnLeave(Level level, BlockPos pos, CallbackInfo ci) {
        if (!RegionStrawBeds.allowsDestroyOnLeave(level, pos)) {
            ci.cancel();
        }
    }
}
