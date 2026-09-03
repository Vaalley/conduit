package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Region protection against fluid flow (issue #49, second half): water or lava
 * a stranger pours just past a region's border must not creep across it. Every
 * step a flowing fluid takes — down, and out to each side — funnels through
 * {@code spreadTo}, whose {@code direction} points from the fluid to the block
 * it is about to fill, so the source is one block back the other way.
 *
 * <p>A fluid spreads freely within one region and over unclaimed ground; it is
 * only stopped at the face where it would enter a region it is not already in.
 * Fluid already inside a protected region is left flowing — the rule keeps it
 * out, it does not freeze it.
 */
@Mixin(FlowingFluid.class)
public abstract class RegionFluidSpreadMixin {

    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void mctraveler$keepFluidOutOfRegions(
            LevelAccessor level,
            BlockPos pos,
            BlockState blockState,
            Direction direction,
            FluidState fluidState,
            CallbackInfo ci) {
        if (level instanceof Level world
                && !Hooks.allowsFluidSpread(world, pos.relative(direction.getOpposite()), pos)) {
            ci.cancel();
        }
    }
}
