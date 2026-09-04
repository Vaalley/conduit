package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Donator perk's `/deathmessage`: {@code CombatTracker.getDeathMessage()}
 * is the single Component vanilla builds for a death — reused verbatim for
 * both the death-screen packet and the chat broadcast (team-visibility rules
 * and all) — so substituting the return value here, rather than touching
 * either downstream use, is the one seam that overrides both consistently.
 *
 * <p>{@code require = 0}: unverifiable field descriptor in this build (no
 * ProGuard mappings — {@code docs/dev-loop.md}); failing to apply just leaves
 * every death message vanilla's own.
 */
@Mixin(CombatTracker.class)
public abstract class CustomDeathMessageMixin {

    @Shadow
    @Final
    private LivingEntity mob;

    @Inject(method = "getDeathMessage", at = @At("RETURN"), cancellable = true, require = 0)
    private void mctraveler$customDeathMessage(CallbackInfoReturnable<Component> cir) {
        if (!(mob instanceof ServerPlayer player)) {
            return;
        }
        Component custom = Hooks.customDeathMessage(player);
        if (custom != null) {
            cir.setReturnValue(custom);
        }
    }
}
