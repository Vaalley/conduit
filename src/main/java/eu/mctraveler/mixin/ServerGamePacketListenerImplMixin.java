package eu.mctraveler.mixin;

import eu.mctraveler.notepad.NotepadFeature;
import java.util.List;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Notepad's packet hooks (spec User Stories 26-27): while a player has an
 * edit session open, the vanilla packets a book-editing client sends are the
 * session's events — Done becomes the save, and the packets that would move the
 * stand-in book out of its slot become cancellations. Without a session every
 * handler is left entirely to vanilla.
 *
 * <p>Threading: {@code handleEditBook} arrives on the netty thread (vanilla
 * defers it through its async text filter), so the save hops to the server
 * thread itself. The other handlers start with vanilla's
 * ensure-running-on-same-thread re-dispatch; injecting at HEAD means running
 * twice — once on the netty thread, where these hooks deliberately do nothing,
 * and once on the server thread, where they act before vanilla applies the
 * packet (so the slot is restored before a click or slot switch touches it).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    /** Done pressed: consume the edit — the pages go to the store, never onto the item. */
    @Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
    private void mctraveler$captureNotepadSave(ServerboundEditBookPacket packet, CallbackInfo ci) {
        ServerPlayer editor = this.player;
        if (!NotepadFeature.hasSession(editor)) {
            return;
        }
        ci.cancel();
        List<String> pages = List.copyOf(packet.pages());
        editor.level().getServer().execute(() -> NotepadFeature.completeSession(editor, pages));
    }

    /** Held-slot switch: cancel first, then vanilla applies the switch to the restored inventory. */
    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"))
    private void mctraveler$cancelNotepadOnHeldItemChange(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        ServerPlayer editor = this.player;
        if (!editor.level().getServer().isSameThread()) {
            return;
        }
        int slot = packet.getSlot();
        if (slot < 0 || slot >= Inventory.getSelectionSize()) {
            return; // invalid: vanilla ignores the packet, so must we
        }
        if (slot == editor.getInventory().getSelectedSlot()) {
            return; // not a change
        }
        NotepadFeature.cancelSession(editor);
    }

    /** Any inventory click: cancel first, so the click lands on the restored inventory. */
    @Inject(method = "handleContainerClick", at = @At("HEAD"))
    private void mctraveler$cancelNotepadOnInventoryClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        ServerPlayer editor = this.player;
        if (!editor.level().getServer().isSameThread()) {
            return;
        }
        NotepadFeature.cancelSession(editor);
    }

    /** Dropping or offhand-swapping the held item would eject the stand-in book: cancel first. */
    @Inject(method = "handlePlayerAction", at = @At("HEAD"))
    private void mctraveler$cancelNotepadOnItemToss(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        ServerboundPlayerActionPacket.Action action = packet.getAction();
        if (action != ServerboundPlayerActionPacket.Action.DROP_ITEM
                && action != ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
                && action != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
            return;
        }
        ServerPlayer editor = this.player;
        if (!editor.level().getServer().isSameThread()) {
            return;
        }
        NotepadFeature.cancelSession(editor);
    }
}
