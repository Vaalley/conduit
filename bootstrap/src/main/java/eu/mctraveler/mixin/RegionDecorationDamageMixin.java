package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Region protection for placed decoration — an item frame, a painting, a leash
 * knot. {@code BlockAttachedEntity.hurtServer} kills the entity and drops its
 * contents directly, without going through {@code hurtOrSimulate}, so
 * {@link RegionEntityDamageMixin} (which the player-arrow path does hit) never
 * sees a creeper blast or a skeleton's arrow reach one; {@code ItemFrame} has
 * its own {@code hurtServer} that pops the framed item out before the parent's
 * ever runs, so both are guarded.
 *
 * <p>Inside a region, nothing breaks one but a member removing it — every other
 * hit is ignored, no flag and no message.
 */
@Mixin({BlockAttachedEntity.class, ItemFrame.class})
public abstract class RegionDecorationDamageMixin {

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void mctraveler$protectDecoration(
            ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!Hooks.allowsEntityDamage((Entity) (Object) this, source)) {
            cir.setReturnValue(false);
        }
    }
}
