package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the temporary arena's gateways from reaching the shared End. */
@Mixin(EndGatewayBlock.class)
public abstract class ArenaEndGatewayMixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void mctraveler$sealArenaGateway(
        ServerLevel level,
        Entity entity,
        BlockPos portalEntryPos,
        CallbackInfoReturnable<TeleportTransition> cir
    ) {
        if (Hooks.isArena(level)) {
            Hooks.arenaGatewayBlocked(level, entity);
            cir.setReturnValue(null);
        }
    }
}
