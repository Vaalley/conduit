package eu.mctraveler.mixin;

import eu.mctraveler.region.RegionEnvironment;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Region protection against pistons (spec User Story 35): one outside a region
 * cannot push blocks into it, and cannot pull blocks out of it.
 *
 * <p>The hook is the resolver rather than the piston, because the resolver is
 * where a piston asks "can this structure move?" and where the answer already
 * knows every block involved. Answering no is the same answer obsidian gives:
 * vanilla then refuses the extension outright, and on a sticky retraction
 * leaves the arm to come home empty — both of them long-settled code paths, and
 * neither of them leaves a piston half-moved.
 */
@Mixin(PistonStructureResolver.class)
public abstract class RegionPistonMixin {

    @Shadow
    @Final
    private Level level;

    @Shadow
    @Final
    private BlockPos pistonPos;

    @Shadow
    @Final
    private boolean extending;

    @Shadow
    @Final
    private Direction pistonDirection;

    @Shadow
    @Final
    private Direction pushDirection;

    @Shadow
    @Final
    private List<BlockPos> toPush;

    @Shadow
    @Final
    private List<BlockPos> toDestroy;

    @Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
    private void mctraveler$keepPistonsOutOfRegions(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        // An extending piston also puts its arm down a block ahead; a
        // retracting one is only taking its own arm back.
        BlockPos armLands = this.extending ? this.pistonPos.relative(this.pistonDirection) : null;
        boolean allowed = RegionEnvironment.allowsPistonMove(
                this.level, this.pistonPos, armLands, this.toPush, this.toDestroy, this.pushDirection);
        if (!allowed) {
            cir.setReturnValue(false);
        }
    }
}
