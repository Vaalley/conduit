package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Donator perk's "markdown" on signs: vanilla builds every sign line as a
 * plain {@code Component.literal} of the raw text — no {@code §} parsing
 * happens anywhere in that path — so a `%`-coded line only turns into real
 * colors/decorations by replacing the Components after vanilla has already
 * applied and broadcast the (uncolored) edit.
 *
 * <p>{@code @At("TAIL")} targets only {@code updateSignText}'s one success
 * return (Sponge Mixin's own definition of TAIL): the method returns early,
 * before this point, for a non-editable or too-far-away sign, so this never
 * fires on a rejected edit. {@code setText} (public API, also used by
 * `updateText`) does the actual field write and the block-update broadcast in
 * one call — no need to replicate `markUpdated`'s `sendBlockUpdated` here.
 */
@Mixin(SignBlockEntity.class)
public abstract class DonatorSignMarkdownMixin {

    @Shadow
    public abstract SignText getText(boolean isFrontText);

    @Shadow
    public abstract boolean setText(SignText text, boolean isFrontText);

    @Inject(method = "updateSignText", at = @At("TAIL"), require = 0)
    private void mctraveler$applyDonatorMarkdown(
            Player player, boolean isFrontText, List<FilteredText> lines, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer editor)) {
            return;
        }
        SignText text = getText(isFrontText);
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            Component styled = Hooks.markdownLineFor(editor, lines.get(i).raw());
            if (styled == null) {
                continue;
            }
            text = text.setMessage(i, styled);
            changed = true;
        }
        if (changed) {
            setText(text, isFrontText);
        }
    }
}
