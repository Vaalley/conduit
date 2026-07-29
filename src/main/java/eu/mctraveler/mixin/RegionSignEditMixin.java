package eu.mctraveler.mixin;

import eu.mctraveler.region.RegionProtection;
import java.util.List;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Region protection for sign editing (inventory §2.8): the text a player types
 * into a sign is a change to that block, and a region refuses it like any
 * other. Fabric has no interaction event for it — the sign text arrives as its
 * own packet — so this is the hook.
 *
 * <p>Injected into {@code updateSignText} rather than the packet handler:
 * vanilla runs the incoming lines through its text filter and hands the result
 * back on the server thread, and that is where the region state may be read.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class RegionSignEditMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
    private void mctraveler$protectSignEdit(
            ServerboundSignUpdatePacket packet, List<FilteredText> lines, CallbackInfo ci) {
        ServerPlayer editor = this.player;
        if (!RegionProtection.allowsBlockChange(editor, editor.level(), packet.getPos())) {
            ci.cancel();
        }
    }
}
