package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Admin mode (/admin): the one place vanilla decides what a player may do. An
 * operator who has not switched admin mode on gets no permissions at all, and a
 * moderator who has gets admin level (the commands beyond their list are hidden
 * by {@link StaffCommandNodeMixin}).
 */
@Mixin(MinecraftServer.class)
public abstract class StaffPermissionsMixin {
    @Inject(method = "getProfilePermissions", at = @At("RETURN"), cancellable = true)
    private void mctraveler$staffPermissions(
            NameAndId id, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        cir.setReturnValue(Hooks.profilePermissions((MinecraftServer) (Object) this, id, cir.getReturnValue()));
    }
}
