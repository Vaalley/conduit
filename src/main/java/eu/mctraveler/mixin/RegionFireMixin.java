package eu.mctraveler.mixin;

import eu.mctraveler.region.RegionEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Region protection against fire (spec User Story 35): a fire neither burns a
 * region's blocks away nor catches inside it, unless the region flies
 * {@code ENABLE_FIRE_DAMAGE}.
 *
 * <p>Everything fire does to blocks leaves through one of two doors, and both
 * of them name the block being changed rather than the flame doing it — which
 * is what the rule needs, because the fire may well be burning outside the
 * region it is eating into:
 *
 * <ul>
 *   <li>{@code checkBurnOut} — the six neighbours a fire consumes each tick,
 *       leaving air or more fire behind (and priming any TNT it finds).
 *   <li>{@code getIgniteOdds} — how willing an empty space around the fire is
 *       to catch. Nought means it never does, which is the whole of the spread
 *       rule: it is the one caller, the spread loop itself.
 * </ul>
 *
 * <p>Fire already burning inside a protected region is left alone: the blocks
 * are protected, not the air above them.
 */
@Mixin(FireBlock.class)
public abstract class RegionFireMixin {

    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void mctraveler$spareRegionBlocksFromBurning(
            Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
        if (!RegionEnvironment.allowsFireDamage(level, pos)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "getIgniteOdds(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)I",
            at = @At("HEAD"),
            cancellable = true)
    private void mctraveler$keepFireOutOfRegions(
            LevelReader level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        // Declared against LevelReader, always a real world at the one call
        // site; anything else has no dimension to find regions in anyway.
        if (level instanceof Level world && !RegionEnvironment.allowsFireDamage(world, pos)) {
            cir.setReturnValue(0);
        }
    }
}
