package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Region protection for splash potions — {@code POTIONS}, "Allows use of
 * potions by others": a non-member's splash potion effect is kept off
 * whoever it lands on inside someone else's region unless it flies the flag.
 *
 * <p>Filters the affected-entity list {@code onHitAsPotion} builds, before it
 * applies any effect, the same shape as {@link RegionExplosionEntityMixin}.
 */
@Mixin(ThrownSplashPotion.class)
public abstract class RegionPotionSplashMixin {

    @Redirect(
            method = "onHitAsPotion",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<LivingEntity> mctraveler$filterProtectedTargets(
            Level level, Class<LivingEntity> entityClass, AABB box) {
        ThrownSplashPotion self = (ThrownSplashPotion) (Object) this;
        List<LivingEntity> found = level.getEntitiesOfClass(entityClass, box);
        found.removeIf(entity -> !Hooks.allowsPotionEffect(level, self.getOwner(), entity));
        return found;
    }
}
