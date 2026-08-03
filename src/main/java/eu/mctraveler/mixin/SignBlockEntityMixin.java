package eu.mctraveler.mixin;

import eu.mctraveler.sign.SignFeature;
import eu.mctraveler.sign.SignSourceAccess;
import java.util.List;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Re-renders submitted sign lines and stores their authored markup.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin implements SignSourceAccess {

    @Unique
    private static final String MCTRAVELER$SOURCE_KEY = "mctraveler:sign_sources";

    @Unique
    private final String[] mctraveler$frontSources = new String[4];

    @Unique
    private final String[] mctraveler$backSources = new String[4];

    @Inject(method = "setMessages", at = @At("RETURN"), cancellable = true)
    private void mctraveler$renderSubmittedLines(
            Player player,
            List<FilteredText> lines,
            SignText text,
            CallbackInfoReturnable<SignText> cir) {
        cir.setReturnValue(SignFeature.renderSubmittedLines(player, lines, cir.getReturnValue()));
    }

    @Inject(
            method = "updateSignText",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/SignBlockEntity;setAllowedPlayerEditor(Ljava/util/UUID;)V"
            )
    )
    private void mctraveler$reconcileSource(
            Player player,
            boolean front,
            List<FilteredText> lines,
            CallbackInfo ci) {
        SignFeature.reconcileSubmittedLines(
                (SignBlockEntity) (Object) this,
                front,
                lines);
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void mctraveler$saveSources(ValueOutput output, CallbackInfo ci) {
        if (!mctraveler$hasSources(mctraveler$frontSources)
                && !mctraveler$hasSources(mctraveler$backSources)) {
            return;
        }
        ValueOutput sources = output.child(MCTRAVELER$SOURCE_KEY);
        mctraveler$saveFace(sources, "front", mctraveler$frontSources);
        mctraveler$saveFace(sources, "back", mctraveler$backSources);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void mctraveler$loadSources(ValueInput input, CallbackInfo ci) {
        java.util.Arrays.fill(mctraveler$frontSources, null);
        java.util.Arrays.fill(mctraveler$backSources, null);
        input.child(MCTRAVELER$SOURCE_KEY).ifPresent(sources -> {
            mctraveler$loadFace(sources, "front", mctraveler$frontSources);
            mctraveler$loadFace(sources, "back", mctraveler$backSources);
        });
    }

    @Unique
    private static boolean mctraveler$hasSources(String[] sources) {
        for (String source : sources) {
            if (source != null && !source.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static void mctraveler$saveFace(
            ValueOutput sources,
            String face,
            String[] values) {
        if (!mctraveler$hasSources(values)) {
            return;
        }
        ValueOutput lines = sources.child(face);
        for (int index = 0; index < values.length; index++) {
            String value = values[index];
            if (value != null && !value.isEmpty()) {
                lines.putString(Integer.toString(index), value);
            }
        }
    }

    @Unique
    private static void mctraveler$loadFace(
            ValueInput sources,
            String face,
            String[] values) {
        ValueInput lines = sources.childOrEmpty(face);
        for (int index = 0; index < values.length; index++) {
            values[index] = lines.getString(Integer.toString(index)).orElse(null);
        }
    }

    @Override
    public String mctraveler$getSource(boolean front, int line) {
        return (front ? mctraveler$frontSources : mctraveler$backSources)[line];
    }

    @Override
    public void mctraveler$setSource(boolean front, int line, String source) {
        (front ? mctraveler$frontSources : mctraveler$backSources)[line] = source;
    }
}
