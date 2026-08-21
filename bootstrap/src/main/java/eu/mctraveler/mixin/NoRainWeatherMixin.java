package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Rewrites weather packets only for a viewer who enabled {@code /norain}.
 *
 * <p>Every clientbound packet funnels through
 * {@code ServerCommonPacketListenerImpl.send(Packet, ChannelFutureListener)}, the one
 * place that knows both the packet and the recipient. The single-argument overload
 * delegates to it in 26.2, so this two-argument seam covers both overloads while keeping
 * the rewrite strictly per connection.
 *
 * <p>Configuration and login connections have no play-phase player and are left alone.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class NoRainWeatherMixin {

    @ModifyVariable(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0)
    private Packet<?> mctraveler$hideWeather(Packet<?> packet) {
        if (!((Object) this instanceof ServerGamePacketListenerImpl listener) || listener.player == null) {
            return packet;
        }
        return Hooks.weatherForViewer(listener.player, packet);
    }
}
