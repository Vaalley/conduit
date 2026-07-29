package eu.mctraveler.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import eu.mctraveler.worlds.WorldRouting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Death never crosses Worlds (spec story 22). Vanilla resolves a respawn
 * against the player's stored respawn point and, failing that, against the
 * server's single overworld — which is Primary's, wherever the player died.
 *
 * <p>This is the one place every respawn is decided: the player list calls it
 * when a death is confirmed, and {@code EndPortalBlock} calls it for a player
 * stepping out of the End. Rewriting its answer therefore covers both, and
 * covers them before the player has been moved anywhere, so there is no visible
 * detour through the wrong World.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRespawnMixin {

    @ModifyReturnValue(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"))
    private TeleportTransition mctraveler$respawnInTheWorldOfDeath(TeleportTransition transition) {
        return WorldRouting.withinDeathWorld((ServerPlayer) (Object) this, transition);
    }
}
