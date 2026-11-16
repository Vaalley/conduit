package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A chest boat in a region cannot be shoved around by someone who is not a member,
 * so it can no longer be pushed out of the region (and its chest with it). The boat
 * simply stays put when a stranger walks into it.
 */
@Mixin(AbstractBoat.class)
public abstract class RegionBoatPushMixin {
    @Inject(method = "push", at = @At("HEAD"), cancellable = true)
    private void mctraveler$strangersCannotPushChestBoats(Entity pusher, CallbackInfo ci) {
        if (!Hooks.allowsBoatPush((AbstractBoat) (Object) this, pusher)) {
            ci.cancel();
        }
    }
}
