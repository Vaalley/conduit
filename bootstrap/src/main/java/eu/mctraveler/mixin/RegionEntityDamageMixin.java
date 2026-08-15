package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Protects the target region before {@link Entity#hurtOrSimulate} dispatches to
 * each entity type's damage implementation. This common final method covers
 * living entities, armor stands, item frames, vehicles, and every other entity
 * without maintaining a parallel list of damage overrides.
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
