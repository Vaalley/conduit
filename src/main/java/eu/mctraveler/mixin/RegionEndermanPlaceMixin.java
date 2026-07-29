package eu.mctraveler.mixin;

import eu.mctraveler.region.RegionEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The other half of the enderman rule (spec User Story 35): one cannot put a
 * block down inside a region either. A block that appears uninvited in
 * somebody's build is as much a change to it as one that goes missing.
 *
 * <p>The goal already asks itself whether the spot will do; this adds the
 * region to that question, so the enderman simply keeps looking — and keeps
 * carrying — rather than being caught mid-place.
 */
@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanLeaveBlockGoal")
public abstract class RegionEndermanPlaceMixin {

    @Shadow
    @Final
    private EnderMan enderman;

    @Inject(method = "canPlaceBlock", at = @At("HEAD"), cancellable = true)
    private void mctraveler$keepEndermanBlocksOutOfRegions(
            Level level,
            BlockPos pos,
            BlockState carried,
            BlockState targetState,
            BlockState belowState,
            BlockPos below,
            CallbackInfoReturnable<Boolean> cir) {
        if (!RegionEnvironment.allowsCreatureBlockChange(level, pos, this.enderman)) {
            cir.setReturnValue(false);
        }
    }
}
