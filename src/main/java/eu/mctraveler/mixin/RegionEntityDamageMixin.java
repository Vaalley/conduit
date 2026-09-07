package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Covers non-living targets and direct damage paths before
 * {@link Entity#hurtOrSimulate}. Living targets also pass through Fabric's
 * {@code ServerLivingEntityEvents.ALLOW_DAMAGE} hook because mob melee invokes
 * {@code hurtServer} directly.
 */
@Mixin(Entity.class)
public abstract class RegionEntityDamageMixin {

    @Inject(method = "hurtOrSimulate", at = @At("HEAD"), cancellable = true)
    private void mctraveler$protectEntityFromPlayerDamage(
            DamageSource source,
            float amount,
            CallbackInfoReturnable<Boolean> cir) {
        Entity target = (Entity) (Object) this;
        if (!Hooks.allowsEntityDamage(target, source)) {
            cir.setReturnValue(false);
        }
    }
}
