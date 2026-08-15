package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla reads {@code getTabListDisplayName} when building every player-info packet, so
 * overriding it here covers the initial ADD_PLAYER broadcast as well as the periodic
 * display-name refreshes {@code TabListFeature} sends as latency updates. The name's
 * format lives in {@code TabListFeature.tabDisplayName}.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void mctraveler$tabListDisplayName(CallbackInfoReturnable<Component> cir) {
        Component name = Hooks.tabDisplayName((ServerPlayer) (Object) this);
        if (name != null) {
            cir.setReturnValue(name);
        }
    }
}
