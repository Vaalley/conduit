package eu.mctraveler.mixin;

import eu.mctraveler.tablist.SpectatorVisibility;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Issue #20: a Spectator's tab-list name is italicised/greyed for every viewer by vanilla
 * client code reacting purely to the {@code GameType} on their
 * {@link ClientboundPlayerInfoUpdatePacket} entry — a tell that lets a cheater notice
 * exactly when an admin is spectating them. Vanilla builds one identical packet (from the
 * live {@code GameType}) and sends it unchanged to every connection, so the only seam that
 * can show a non-admin viewer something different from what the spectating admin's own
 * client receives is the outgoing packet itself, here, per connection — see
 * {@link SpectatorVisibility} for what gets substituted and why.
 *
 * <p>{@code ServerPlayerConnection} (not the {@code ServerGamePacketListenerImpl} field
 * {@code ServerGamePacketListenerImplMixin} shadows) is the seam back to the viewer: an
 * interface {@code instanceof} check compiles regardless of this mixin's own hierarchy, so
 * it needs no field-visibility assumption and doubles as the phase guard — connections
 * still in the configuration phase, which never implement it, are skipped automatically.
 *
 * <p>{@code require = 0}: {@link SpectatorVisibility} locates everything it needs about the
 * entry/packet shapes by reflection rather than a hand-verified descriptor (this build
 * ships no ProGuard mappings — {@code docs/dev-loop.md}), and this injector's own target
 * descriptor is equally unverifiable here. Either failing to resolve leaves Spectator
 * visible to everyone — today's behaviour — instead of {@code mctraveler.mixins.json}'s
 * {@code defaultRequire: 1} refusing the whole mod to load.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class SpectatorVisibilityMixin {

    @ModifyVariable(
        method = "send(Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD"),
        argsOnly = true,
        require = 0
    )
    private Packet<?> mctraveler$hideSpectatorFromNonAdmins(Packet<?> packet) {
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket update)) {
            return packet;
        }
        if (!(this instanceof ServerPlayerConnection connection)) {
            return packet;
        }
        ServerPlayer viewer = connection.getPlayer();
        if (viewer == null) {
            return packet;
        }
        ClientboundPlayerInfoUpdatePacket masked = SpectatorVisibility.maskFor(viewer, update);
        return masked != null ? masked : packet;
    }
}
