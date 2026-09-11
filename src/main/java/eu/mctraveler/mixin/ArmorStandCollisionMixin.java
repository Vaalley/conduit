package eu.mctraveler.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the entity-collision scan for armor stands — Paper's
 * {@code armor-stands-do-collision-entity-lookups} as a mixin.
 *
 * <p>{@code LivingEntity.pushEntities} asks the chunk entity list for every
 * pushable entity overlapping the stand's bounding box, once per stand per
 * tick. An armor stand can neither be pushed ({@code isPushable} is false) nor
 * push anything ({@code doPush} is empty), so the whole query is wasted work —
 * and populated lobby/decoration areas pay it for every stand every tick.
 *
 * <p>The one behaviour this gives up is entity-cramming damage *to* a stand
 * when more than maxEntityCramming entities share its space — the same cost
 * Paper accepts for the option.
 */
@Mixin(LivingEntity.class)
public abstract class ArmorStandCollisionMixin {

    @Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
    private void mctraveler$armorStandsSkipCollisionLookups(CallbackInfo ci) {
        if ((Object) this instanceof ArmorStand) {
            ci.cancel();
        }
    }
}
