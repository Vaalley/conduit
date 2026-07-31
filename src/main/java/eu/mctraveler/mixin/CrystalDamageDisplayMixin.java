package eu.mctraveler.mixin;

import eu.mctraveler.crystal.CrystalDamageDisplay;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Paints each viewer's own crystal energy onto the crystals they are sent
 * (spec User Story 23, deviation 12).
 *
 * <p>Every clientbound packet funnels through
 * {@code ServerCommonPacketListenerImpl.send}, which is the one place that knows
 * both the packet and who it is going to — the two halves of a per-viewer
 * rewrite. Swapping the argument for a repainted copy leaves the server's own
 * inventories untouched; only the wire changes.
 *
 * <p>The single-argument {@code send} delegates here, so hooking this overload
 * covers both.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class CrystalDamageDisplayMixin {

    @ModifyVariable(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0)
    private Packet<?> mctraveler$paintCrystalEnergy(Packet<?> packet) {
        // Only a play-phase connection has a player to read energy from;
        // configuration and login traffic is left entirely alone.
        if (!((Object) this instanceof ServerGamePacketListenerImpl listener) || listener.player == null) {
            return packet;
        }
        return CrystalDamageDisplay.forViewer(listener.player, packet);
    }
}
