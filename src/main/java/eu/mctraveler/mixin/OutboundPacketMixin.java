package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The single seam for per-viewer packet rewrites.
 *
 * <p>Every clientbound play packet passes through
 * {@code ServerCommonPacketListenerImpl.send} once — the one place that knows both
 * the packet and who it is going to. The runtime decides per packet type whether a
 * viewer-specific substitute is needed (spectator/vanish masking, health-score
 * masking, crystal energy bars, hidden weather, {@code <name>} signs); everything
 * else is returned unchanged. The single-argument {@code send} delegates here, so
 * hooking this overload covers both.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class OutboundPacketMixin {

    @ModifyVariable(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0)
    private Packet<?> mctraveler$packetForViewer(Packet<?> packet) {
        // Only a play-phase connection has a player to personalise for;
        // configuration and login traffic is left entirely alone.
        if (!((Object) this instanceof ServerGamePacketListenerImpl listener) || listener.player == null) {
            return packet;
        }
        return Hooks.packetForViewer(listener.player, packet);
    }
}
