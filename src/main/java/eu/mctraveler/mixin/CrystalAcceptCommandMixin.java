package eu.mctraveler.mixin;

import eu.mctraveler.crystal.CrystalRequests;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one command that is not in the command tree (spec User Story 35,
 * deviation 8): {@code /teleportation-crystal-accept}.
 *
 * <p>Nucleus never registered it — it read the raw chat line in Bukkit's
 * command-preprocess event and cancelled it — so it appeared in no client's tab
 * completion and produced no unknown-command error. Registering it in Brigadier
 * would give it both. Taking the command packet before vanilla parses it
 * reproduces Nucleus exactly: the client sends whatever the message's click
 * event says, this hook answers it, and vanilla never learns the command was
 * typed.
 *
 * <p>Threading: {@code handleChatCommand} hands its work to {@code tryHandleChat}
 * and so has no same-thread guard of its own — HEAD is the netty thread. The
 * cheap string test runs there; everything that touches the world is queued onto
 * the server thread.
 *
 * <p>A pure shim, per ADR 0002: the rules are in {@link CrystalRequests}.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CrystalAcceptCommandMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void mctraveler$interceptCrystalAccept(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        String command = packet.command();
        if (!CrystalRequests.isAcceptCommand(command)) {
            return;
        }
        ci.cancel();
        ServerPlayer acceptor = this.player;
        acceptor.level().getServer().execute(() -> CrystalRequests.accept(acceptor, command));
    }
}
