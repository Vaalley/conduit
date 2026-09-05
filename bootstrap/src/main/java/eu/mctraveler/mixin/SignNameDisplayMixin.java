package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Renders the {@code <name>} sign-markdown token as each viewer's own name
 * (see {@link eu.mctraveler.text.SignNames}).
 *
 * <p>Signs carry one text to every client, so a per-reader token can only be
 * resolved on the way out. {@code ServerCommonPacketListenerImpl.send} is the one
 * place that knows both the packet and its recipient: a sign block-entity packet
 * is swapped for a copy re-serialised with that viewer's name, and a chunk packet
 * for a chunk holding a token sign triggers a follow-up block-entity packet.
 * The server's stored signs are never touched.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class SignNameDisplayMixin {

    @ModifyVariable(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0)
    private Packet<?> mctraveler$personalizeSignNames(Packet<?> packet) {
        // Only a play-phase connection has a player to name; configuration and
        // login traffic is left entirely alone.
        if (!((Object) this instanceof ServerGamePacketListenerImpl listener) || listener.player == null) {
            return packet;
        }
        return Hooks.personalizeSignsForViewer(listener.player, packet);
    }
}
