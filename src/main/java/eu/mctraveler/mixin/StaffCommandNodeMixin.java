package eu.mctraveler.mixin;

import com.mojang.brigadier.tree.CommandNode;
import eu.mctraveler.hooks.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides the vanilla commands a staff level does not include (a moderator's
 * list is short; /trigger is for full admins only). {@code canUse} is what both
 * the client's command tree and the parser ask, so one hook covers suggestions
 * and execution.
 */
@Mixin(value = CommandNode.class, remap = false)
public abstract class StaffCommandNodeMixin<S> {
    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void mctraveler$staffHidesCommand(S source, CallbackInfoReturnable<Boolean> cir) {
        if (Hooks.hidesCommand((CommandNode<?>) (Object) this, source)) {
            cir.setReturnValue(false);
        }
    }
}
