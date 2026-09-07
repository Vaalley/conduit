package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the two pressure-plate rules. Ordinary plates honor
 * {@code DISABLE_PUBLIC_REDSTONE_TRIGGERS} for non-members; weighted plates
 * remain automation-friendly for every entity unless their dedicated
 * {@code DISABLE_WEIGHTED_PRESSURE_PLATES} flag disables the plate wholesale.
 */
@Mixin(BasePressurePlateBlock.class)
public abstract class RegionPressurePlateMixin {

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void mctraveler$keepStrangersOffThePlate(
            BlockState state,
            Level level,
            BlockPos pos,
            Entity entity,
            InsideBlockEffectApplier effectApplier,
            boolean isPrecise,
            CallbackInfo ci) {
        if (!Hooks.allowsPressurePlate(state, level, pos, entity)) {
            ci.cancel();
        }
    }
}
