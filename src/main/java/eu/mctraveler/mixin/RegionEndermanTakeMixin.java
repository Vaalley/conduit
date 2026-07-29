package eu.mctraveler.mixin;

import eu.mctraveler.region.RegionEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Region protection against endermen carrying a region's blocks away (spec User
 * Story 35). An enderman does not destroy the block it takes, so it never
 * reaches the general mob-griefing hook — it removes the block and puts it in
 * its hands in the same breath.
 *
 * <p>The block it is eyeing is therefore made to look like empty air, which is
 * the one refusal that leaves the goal's own bookkeeping intact: nothing is
 * holdable, so nothing is removed and nothing is carried. Refusing the removal
 * alone would leave the enderman holding a copy of a block still in the ground.
 */
@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanTakeBlockGoal")
public abstract class RegionEndermanTakeMixin {

    @Shadow
    @Final
    private EnderMan enderman;

    @Redirect(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState mctraveler$hideRegionBlocksFromEndermen(Level level, BlockPos pos) {
        if (!RegionEnvironment.allowsCreatureBlockChange(level, pos, this.enderman)) {
            return Blocks.AIR.defaultBlockState();
        }
        return level.getBlockState(pos);
    }
}
