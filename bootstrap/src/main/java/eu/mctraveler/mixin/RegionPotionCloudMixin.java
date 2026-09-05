package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import java.util.List;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Region protection for lingering potions' area-effect cloud — the same
 * {@code POTIONS} rule as {@link RegionPotionSplashMixin}, reached from the
 * cloud's own per-tick victim sweep ({@code AreaEffectCloud.serverTick})
 * instead of a splash potion's one-shot impact.
 */
@Mixin(AreaEffectCloud.class)
public abstract class RegionPotionCloudMixin {

    @Redirect(
            method = "serverTick",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<LivingEntity> mctraveler$filterProtectedTargets(
            Level level, Class<LivingEntity> entityClass, AABB box) {
        AreaEffectCloud self = (AreaEffectCloud) (Object) this;
        List<LivingEntity> found = level.getEntitiesOfClass(entityClass, box);
        found.removeIf(entity -> !Hooks.allowsPotionEffect(level, self.getOwner(), entity));
        return found;
    }
}
