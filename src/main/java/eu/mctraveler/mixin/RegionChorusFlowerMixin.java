package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Region protection for a real, existing vanilla mechanic that had a gap:
 * {@code ChorusFlowerBlock.onProjectileHit} unconditionally destroys the
 * flower it hit for any of arrow, trident, firework rocket, snowball, egg,
 * fireball, or wind charge — no membership check of any kind. This closes
 * that gap by reusing the ordinary block-change rule
 * ({@code RegionProtection.allowsProjectileBlockChange}), reached from a
 * projectile's block hit instead of a player's own click, so it already
 * respects {@code PUBLIC} and membership exactly as every other block-change
 * rule does; a non-player-shot projectile (a dispenser-fired arrow) is
 * unaffected.
 */
@Mixin(ChorusFlowerBlock.class)
public abstract class RegionChorusFlowerMixin {

    @Inject(method = "onProjectileHit", at = @At("HEAD"), cancellable = true)
    private void mctraveler$protectChorusFlower(
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
