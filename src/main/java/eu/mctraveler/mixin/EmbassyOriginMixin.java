package eu.mctraveler.mixin;

import eu.mctraveler.embassy.EmbassyOrigins;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Catches a player on their way into (or out of) the embassies dimension, in
 * the last instant that still knows where they are standing.
 *
 * <p>Every teleport a player can make ends up here: {@code teleportTo} builds a
 * transition and calls this, which is what {@code /tp}, the crystal menu,
 * {@code /embassy create} and portals all go through. Fabric's
 * {@code AFTER_PLAYER_CHANGE_LEVEL} would be the natural seam but it fires
 * after the move, carrying only the two levels — never the position the player
 * left, which is the whole of what an origin is.
 *
 * <p>A pure shim, per ADR 0002: the rules are in
 * {@link eu.mctraveler.embassy.EmbassyOrigins#beforeTeleport}.
 */
@Mixin(ServerPlayer.class)
public abstract class EmbassyOriginMixin {

    @Inject(
        method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
        at = @At("HEAD")
    )
    private void mctraveler$recordEmbassyOrigin(
        TeleportTransition transition,
        CallbackInfoReturnable<ServerPlayer> cir
    ) {
        EmbassyOrigins.beforeTeleport((ServerPlayer) (Object) this, transition.newLevel().dimension());
    }
}
