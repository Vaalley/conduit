package eu.mctraveler.mixin;

import eu.mctraveler.region.RegionEnvironment;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Region protection against explosions of every source — TNT, a creeper, a bed
 * or anchor lit in the wrong dimension, an end crystal, a ghast fireball (spec
 * User Story 35).
 *
 * <p>Every explosion in the game is a {@link ServerExplosion}, and every one of
 * them works out the blocks it reaches once, then hands that same list to the
 * block interaction and to the fire it may leave behind. Taking the region's
 * blocks out of that list is therefore the whole rule, for every source at
 * once: the region is neither broken nor set alight.
 *
 * <p>What the explosion does to players, mobs and items is deliberately left
 * alone — it is computed from the centre and radius, not from this list.
 */
@Mixin(ServerExplosion.class)
public abstract class RegionExplosionMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
    private void mctraveler$spareRegionBlocks(CallbackInfoReturnable<List<BlockPos>> cir) {
        List<BlockPos> exploded = cir.getReturnValue();
        if (exploded.isEmpty()) {
            return;
        }
        exploded.removeIf(pos -> !RegionEnvironment.allowsExplosionDamage(this.level, pos));
    }
}
