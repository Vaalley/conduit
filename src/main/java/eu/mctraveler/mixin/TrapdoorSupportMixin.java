package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TrapDoorBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inside a region a trapdoor keeps supporting the centre of what stands on it
 * or hangs from it, open or closed (issue #99). Vanilla drops a lantern the
 * moment the trapdoor beneath it opens; with the {@code TRAPDOORS} flag on,
 * that let any stranger take it.
 */
@Mixin(Block.class)
public abstract class TrapdoorSupportMixin {

    @Inject(method = "canSupportCenter", at = @At("HEAD"), cancellable = true)
    private static void mctraveler$trapdoorStillSupports(
            LevelReader level, BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if ((direction == Direction.UP || direction == Direction.DOWN)
                && level.getBlockState(pos).getBlock() instanceof TrapDoorBlock
                && Hooks.trapdoorKeepsSupporting(level, pos)) {
            cir.setReturnValue(true);
        }
    }
}
