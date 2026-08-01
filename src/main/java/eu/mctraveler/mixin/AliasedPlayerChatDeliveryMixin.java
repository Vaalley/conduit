package eu.mctraveler.mixin;

import eu.mctraveler.identity.IdentityRemaps;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FilterMask;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inbound chat for the two aliased identities (ticket 22, deviation 58):
 * everything player-voiced the server would send them — signed player chat and
 * disguised chat alike — is delivered as a system message instead, decorated
 * server-side with the same {@link ChatType.Bound} a client would have applied.
 *
 * <p>Live symptom: the aliased players could send (ticket 21's exemptions) and
 * could see system traffic — private messages, presence lines — but no one
 * else's chat. Working through the mapped 26.2 jar, the per-recipient delivery
 * of player chat is identical for every connection on the server side; what is
 * <em>not</em> identical is the set of gates a vanilla client applies to
 * player-voiced chat, and only to player-voiced chat, none of which the server
 * can see or influence: the friends-only restriction drops any sender failing
 * {@code Minecraft.isFriendOnlyRestricted} with no error line, a profile or
 * options chat restriction removes {@code CHAT_RECEIVE_PLAYER_MESSAGES} and the
 * chat HUD then filters the whole PLAYER source at render time (while sending
 * remains ungated — {@code ChatScreen.handleChatInput} never consults it), and
 * a commands-only visibility stops the server sending player chat at all
 * ({@code acceptsChatMessages()}) while plain sends still broadcast (only
 * {@code HIDDEN} blocks them). Which of these holds on the two players' actual
 * clients cannot be determined from the server; the system channel passes every
 * one of them, and it is the channel this pair demonstrably receives.
 *
 * <p>So, mirroring ticket 21's outbound shape: the one seam every player-voiced
 * delivery passes through ({@code ServerPlayer.sendChatMessage} — reached by
 * both {@code OutgoingChatMessage} variants, before the chat-visibility gate)
 * is redirected onto {@code sendSystemMessage} for aliased recipients only.
 * Rendering is identical — the decorated line is exactly what their client
 * would have built — and every other player is untouched. The cost, matching
 * deviation 57's blast radius: chat these two players <em>receive</em> carries
 * no signature on their client, so they cannot chat-report it there; everyone
 * else's copy of the same lines stays signed and reportable.
 */
@Mixin(ServerPlayer.class)
public abstract class AliasedPlayerChatDeliveryMixin {

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void mctraveler$deliverAliasedIncomingChatAsSystem(
            OutgoingChatMessage message, boolean filtered, ChatType.Bound chatType, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!IdentityRemaps.isAliased(self.getUUID())) {
            return;
        }
        ci.cancel();
        Component line = mctraveler$lineAsShown(message, filtered);
        if (line != null) {
            self.sendSystemMessage(chatType.decorate(line));
        }
    }

    /**
     * The line exactly as a vanilla client would have displayed it: the
     * per-recipient filter flag applied as {@code OutgoingChatMessage.Player}
     * does before sending, then the filter mask as the client's
     * {@code ChatListener.showMessageToPlayer} applies it. A fully-filtered
     * message shows nothing, exactly as vanilla shows nothing.
     */
    @Unique
    private static Component mctraveler$lineAsShown(OutgoingChatMessage message, boolean filtered) {
        if (!(message instanceof OutgoingChatMessage.Player player)) {
            return message.content();
        }
        PlayerChatMessage delivered = player.message().filter(filtered);
        if (delivered.isFullyFiltered()) {
            return null;
        }
        FilterMask mask = delivered.filterMask();
        return mask.isEmpty() ? delivered.decoratedContent() : mask.applyWithFormatting(delivered.signedContent());
    }
}
