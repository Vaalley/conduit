package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Issue #47: a vanished admin is in Spectator, but Spectator bodies are still tracked and
 * sent to every viewer. {@code ChunkMap$TrackedEntity.updatePlayer} runs each tick to decide
 * whether a given viewer should see the entity; when the tracked entity is a vanished admin
 * and the viewer is a non-admin, we drop them (mirroring an out-of-range result) so the body
 * never reaches that client — and {@code removePlayer} sends the despawn if they were already
 * tracking it. Unvanishing lets the next tick re-add them normally.
 *
 * <p>{@code require = 0}: the inner-class target descriptor is unverifiable in this build
 * (no ProGuard mappings — {@code docs/dev-loop.md}); failing to apply just leaves vanished
 * bodies visible rather than refusing the whole mod under {@code defaultRequire: 1}.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class VanishTrackingMixin {

    @Shadow @Final private Entity entity;

    @Shadow public abstract void removePlayer(ServerPlayer player);

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void mctraveler$hideVanished(ServerPlayer viewer, CallbackInfo ci) {
        if (entity instanceof ServerPlayer target && Hooks.isVanishedFromViewer(target, viewer)) {
            removePlayer(viewer);
            ci.cancel();
        }
    }
}
