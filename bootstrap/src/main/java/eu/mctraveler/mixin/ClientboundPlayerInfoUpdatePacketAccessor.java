package eu.mctraveler.mixin;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A real, compiled setter for {@link ClientboundPlayerInfoUpdatePacket}'s private
 * {@code entries} field, for {@link eu.mctraveler.tablist.SpectatorVisibility}.
 *
 * <p>The class has no public (or otherwise reachable) constructor taking a ready-made
 * entry list — only {@code (EnumSet<Action>, Collection<ServerPlayer>)}, which recomputes
 * every entry from live {@code ServerPlayer} state, {@code (Action, ServerPlayer)}, and a
 * private network-decode constructor (verified against the real 26.2 jar with
 * {@code javap}; this build ships no ProGuard mappings otherwise). Building a packet with
 * masked entries therefore means: construct one normally with an empty player collection
 * (giving the right {@code actions} and an empty, throwaway entry list), then overwrite
 * that list through this accessor. Mixin generates a real bytecode setter here — no
 * reflection, no unsynchronized final-field write.
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface ClientboundPlayerInfoUpdatePacketAccessor {

    @Accessor("entries")
    @Mutable
    void mctraveler$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
