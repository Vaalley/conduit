package eu.mctraveler.mixin;

import eu.mctraveler.identity.IdentityRemaps;
import java.util.function.BooleanSupplier;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Secure chat for the two aliased identities (spec User Story 42, deviations 6
 * and 8), which vanilla structurally cannot sign.
 *
 * <p>A remapped player's client is handed a uuid that is not the one it
 * authenticated with, and vanilla's client only builds a chat session when the
 * two match ({@code ClientPacketListener.setKeyPair} guards on
 * {@code Minecraft.isLocalPlayer(localGameProfile.id())}). So an aliased player
 * never sends {@code ServerboundChatSessionUpdatePacket} at all, their message
 * encoder stays {@code Encoder.UNSIGNED}, and — with
 * {@code enforce-secure-profile=true} — the server's unsigned decoder answers
 * every line with "Chat disabled due to missing profile public key". Validating
 * their profile public key against the identity they authenticated as cannot
 * help: there is no key to validate, and every *other* client would still
 * reject the session because it validates the key against the aliased profile
 * in its own player list. Exempting exactly these two players is the only route
 * that leaves everyone else's chat signed and reportable.
 *
 * <p>Three narrow exemptions, all keyed off {@link IdentityRemaps#isAliased}:
 * the inbound decoder accepts their unsigned lines; the unsigned-command gate
 * lets their signable-argument commands ({@code /me}) through; and their
 * (necessarily unsigned) messages go out as disguised chat, which no client
 * validates, rather than as player chat, which a client with secure chat
 * enforced would drop unseen. Rendering is identical — both carry the same
 * {@link ChatType.Bound}. Every other player is untouched on all three paths.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class AliasedPlayerSecureChatMixin {

    @Shadow
    public ServerPlayer player;

    @Shadow
    private SignedMessageChain.Decoder signedMessageDecoder;

    @Shadow
    public abstract void sendDisguisedChatMessage(Component content, ChatType.Bound chatType);

    /** Inbound: an aliased player's unsigned chat is accepted instead of refused. */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void mctraveler$acceptAliasedUnsignedChat(
            MinecraftServer server,
            Connection connection,
            ServerPlayer player,
            CommonListenerCookie cookie,
            CallbackInfo ci) {
        if (IdentityRemaps.isAliased(player.getUUID())) {
            BooleanSupplier neverEnforced = () -> false;
            this.signedMessageDecoder = SignedMessageChain.Decoder.unsigned(player.getUUID(), neverEnforced);
        }
    }

    /** Inbound: the same exemption for commands carrying signable arguments. */
    @Redirect(
        method = "performUnsignedChatCommand",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;enforceSecureProfile()Z"))
    private boolean mctraveler$exemptAliasedFromCommandSigning(MinecraftServer server) {
        return server.enforceSecureProfile() && !IdentityRemaps.isAliased(this.player.getUUID());
    }

    /**
     * Outbound: an aliased player's unsigned line reaches every recipient as
     * disguised chat. A recipient whose server enforces secure chat drops
     * unsigned <em>player</em> chat unseen (vanilla's
     * {@code SignedMessageValidator.REJECT_ALL}), which would leave the sender
     * talking to themselves. A signed message — including one from the real
     * account that owns an alias, whose client can sign — is left alone.
     */
    @Inject(method = "sendPlayerChatMessage", at = @At("HEAD"), cancellable = true)
    private void mctraveler$sendAliasedChatAsDisguised(
            PlayerChatMessage message, ChatType.Bound chatType, CallbackInfo ci) {
        if (!message.hasSignature() && IdentityRemaps.isAliased(message.sender())) {
            this.sendDisguisedChatMessage(message.decoratedContent(), chatType);
            ci.cancel();
        }
    }
}
