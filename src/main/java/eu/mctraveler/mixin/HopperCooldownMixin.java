package eu.mctraveler.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Cools a hopper down after a move that went nowhere — the same fix Paper
 * applies ("cooldown when full"): vanilla only sets the 8-tick transfer
 * cooldown after a *successful* move, so a hopper under a full chest — or one
 * pushing into a full container — retries the whole scan every single tick
 * for as long as it stays stuck.
 *
 * <p>On {@code tryMoveItems} returning false the hopper goes on the normal
 * {@code MOVE_ITEM_SPEED} cooldown, so a stuck hopper retries once per 8 ticks
 * instead of once per tick. An already-cooled-down hopper is left alone — the
 * guard matters because {@code tryMoveItems} also answers false early when
 * called (from {@code entityInside}) while the hopper is cooling, and
 * re-arming there would keep it cool forever under an item that cannot move.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperCooldownMixin {

    @Invoker("isOnCooldown")
    abstract boolean mctraveler$isOnCooldown();

    @Invoker("setCooldown")
    abstract void mctraveler$setCooldown(int ticks);

    @Inject(method = "tryMoveItems", at = @At("RETURN"))
    private static void mctraveler$cooldownAfterFailedMove(
        Level level,
        BlockPos pos,
        BlockState state,
        HopperBlockEntity hopper,
        BooleanSupplier getter,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()) {
            HopperCooldownMixin self = (HopperCooldownMixin) (Object) hopper;
            if (!self.mctraveler$isOnCooldown()) {
                self.mctraveler$setCooldown(HopperBlockEntity.MOVE_ITEM_SPEED);
            }
        }
    }
}
