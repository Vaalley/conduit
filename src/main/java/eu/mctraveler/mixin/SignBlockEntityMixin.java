package eu.mctraveler.mixin;

import eu.mctraveler.sign.SignFeature;
import java.util.List;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Re-renders submitted sign lines after vanilla has completed its edit checks.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {

    @Inject(method = "setMessages", at = @At("RETURN"), cancellable = true)
    private void mctraveler$renderSubmittedLines(
            Player player,
            List<FilteredText> lines,
            SignText text,
            CallbackInfoReturnable<SignText> cir) {
        cir.setReturnValue(SignFeature.renderSubmittedLines(player, lines, cir.getReturnValue()));
    }
}
