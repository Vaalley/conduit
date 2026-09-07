package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Region protection for a real vanilla mechanic with a gap:
 * {@code TntBlock.onProjectileHit} primes the TNT for any projectile that is on
 * fire (a flame arrow, a burning trident) with no membership check. This closes
 * that gap by reusing the ordinary block-change rule
 * ({@code RegionProtection.allowsProjectileBlockChange}), reached from the
 * projectile's block hit instead of a player's own click, so it already
 * respects {@code PUBLIC} and membership exactly as every other block-change
 * rule does; a non-player-shot projectile (a dispenser-fired flame arrow) is
 * unaffected. Lighting TNT by hand is covered separately by
 * {@code RegionInteractables.classify} marking {@code TntBlock} members-only.
 */
@Mixin(TntBlock.class)
public abstract class RegionTntProjectileMixin {

    @Inject(method = "onProjectileHit", at = @At("HEAD"), cancellable = true)
    private void mctraveler$protectTnt(
            Level level,
            BlockState state,
            BlockHitResult blockHit,
            Projectile projectile,
            CallbackInfo ci) {
        if (!Hooks.allowsProjectileBlockChange(projectile, level, blockHit.getBlockPos())) {
            ci.cancel();
        }
    }
}
