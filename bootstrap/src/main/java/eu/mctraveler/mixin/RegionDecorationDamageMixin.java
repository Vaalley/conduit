package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Region protection for placed decoration — an item frame, a painting, a leash
 * knot. Two vanilla paths take one out without ever touching
 * {@code hurtOrSimulate} (the seam {@link RegionEntityDamageMixin} guards):
 *
 * <ul>
 *   <li>{@code BlockAttachedEntity.hurtServer} kills and drops in place, and
 *       {@code ItemFrame} has its own {@code hurtServer} that pops the framed
 *       item first — so both are guarded at HEAD;
 *   <li>{@code BlockAttachedEntity.move} / {@code push} destroy the entity on
 *       <em>any</em> displacement, which is how an explosion's knockback (and a
 *       boat, and a minecart) breaks one even when the damage was refused.
 * </ul>
 *
 * <p>Inside a region, nothing breaks one but a member removing it — every other
 * hit or shove is ignored, no flag and no message.
 */
@Mixin({BlockAttachedEntity.class, ItemFrame.class})
public abstract class RegionDecorationDamageMixin {

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void mctraveler$protectDecorationDamage(
            ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!Hooks.allowsEntityDamage((Entity) (Object) this, source)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true, require = 0)
    private void mctraveler$protectDecorationFromShove(
            MoverType type, Vec3 movement, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!Hooks.allowsDecorationMove(self.level(), self.blockPosition())) {
            ci.cancel();
        }
    }

    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void mctraveler$protectDecorationFromPush(double x, double y, double z, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!Hooks.allowsDecorationMove(self.level(), self.blockPosition())) {
            ci.cancel();
        }
    }
}
