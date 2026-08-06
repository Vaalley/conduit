package eu.mctraveler.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import eu.mctraveler.sign.SignFeature;
import eu.mctraveler.sign.SignSourceAccess;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
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
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    @ModifyArg(
            method = "updateSignText",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/SignBlockEntity;updateText(Ljava/util/function/UnaryOperator;Z)Z",
                    ordinal = 0
            ),
            index = 0
    )
    private UnaryOperator<SignText> mctraveler$reconcileSource(
            UnaryOperator<SignText> vanillaUpdate,
            @Local(argsOnly = true) Player player,
            @Local(argsOnly = true) boolean front,
            @Local(argsOnly = true) List<FilteredText> lines) {
        SignBlockEntity sign = (SignBlockEntity) (Object) this;
        return text -> SignFeature.reconcileSubmittedLines(
                sign,
                player,
                front,
                lines,
                vanillaUpdate.apply(text));
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
        Arrays.fill(mctraveler$frontSources, null);
        Arrays.fill(mctraveler$backSources, null);
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
    public String signSource(boolean front, int line) {
        return (front ? mctraveler$frontSources : mctraveler$backSources)[line];
    }

    @Override
    public void setSignSource(boolean front, int line, String source) {
        (front ? mctraveler$frontSources : mctraveler$backSources)[line] = source;
    }
}
