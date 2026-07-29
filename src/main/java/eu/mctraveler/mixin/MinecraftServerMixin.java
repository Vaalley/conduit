package eu.mctraveler.mixin;

import eu.mctraveler.motd.Motd;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands the status/server-list response the server builds to {@link Motd}, which
 * decorates it with the Portal's presence (the exact two-line MOTD and the first-12
 * player sample). A pure shim: the feature logic lives in the Kotlin module. Java per
 * ADR 0002 (mixins stay in Java).
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "buildServerStatus", at = @At("RETURN"), cancellable = true)
    private void mctraveler$decorateServerListStatus(CallbackInfoReturnable<ServerStatus> cir) {
        cir.setReturnValue(Motd.INSTANCE.decorate(cir.getReturnValue(), (MinecraftServer) (Object) this));
    }
}
