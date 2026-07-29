package eu.mctraveler.mixin;

import com.mojang.authlib.GameProfile;
import eu.mctraveler.identity.IdentityRemaps;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The two aliased identities' login-time GameProfile swap (spec User Story 42),
 * the Fabric equivalent of the Portal's TravelPatchFeature remap.
 *
 * <p>{@code startClientVerification} is the one funnel every login path passes
 * through — the Mojang auth thread calls it with the authenticated profile
 * (i.e. after Mojang auth), and the offline and singleplayer paths call it
 * directly from {@code handleHello}. Swapping the profile argument here means
 * the stored {@code authenticatedProfile}, and with it everything built from it
 * downstream — the ServerPlayer, its playerdata file, tab-list entry, region
 * membership checks, and the mod's name cache — sees only the remapped
 * identity. The swap itself (table, case-sensitive match, log line) lives in
 * {@link IdentityRemaps}; every profile not in the table passes through
 * untouched.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @ModifyVariable(
        method = "startClientVerification(Lcom/mojang/authlib/GameProfile;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private GameProfile mctraveler$remapAliasedIdentities(GameProfile profile) {
        return IdentityRemaps.remap(profile);
    }
}
