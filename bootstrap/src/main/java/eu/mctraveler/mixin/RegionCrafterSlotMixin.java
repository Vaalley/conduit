package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Region protection for the one crafter action that is not a slot click:
 * enabling or disabling an input slot arrives as its own packet
 * ({@code ServerboundContainerSlotStateChangedPacket}), which
 * {@link RegionContainerClickMixin} — a hook on {@code clicked} — never sees. A
 * crafter is a view-only container for a non-member (issue #40), so its layout
 * may not be changed either.
 *
 * <p>Injected after the packet's own thread hop so the region state is read on
 * the server thread, the same reason {@code RegionSignEditMixin} targets
 * {@code updateSignText} rather than the raw handler.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class RegionCrafterSlotMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(
            method = "handleContainerSlotStateChanged",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER),
            cancellable = true)
    private void mctraveler$protectCrafterSlotToggle(
            ServerboundContainerSlotStateChangedPacket packet, CallbackInfo ci) {
        if (!Hooks.allowsContainerUse(this.player)) {
            this.player.containerMenu.sendAllDataToRemote();
            ci.cancel();
        }
    }
}
