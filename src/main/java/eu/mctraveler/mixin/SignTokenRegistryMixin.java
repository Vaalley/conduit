package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps {@link eu.mctraveler.text.SignNames}'s live registry of which loaded
 * chunks hold a {@code <name>} sign, so the chunk-load rewrite path stays free
 * whenever the feature is unused.
 *
 * <p>{@code setLevel} rather than {@code loadAdditional}: a sign deserialised on
 * chunk load has its text populated before it is handed a level, so this is the
 * first point at which both the world position and the loaded lines are known.
 */
@Mixin(BlockEntity.class)
public abstract class SignTokenRegistryMixin {

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void mctraveler$noteSignTokens(Level level, CallbackInfo ci) {
        if ((Object) this instanceof SignBlockEntity sign) {
            Hooks.onSignLoadedOrChanged(level, sign.getBlockPos(), sign);
        }
    }

    @Inject(method = "setRemoved", at = @At("TAIL"))
    private void mctraveler$forgetSignTokens(CallbackInfo ci) {
        if ((Object) this instanceof SignBlockEntity sign && sign.getLevel() != null) {
            Hooks.onSignRemoved(sign.getLevel(), sign.getBlockPos());
        }
    }
}
