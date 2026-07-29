package eu.mctraveler.mixin;

import eu.mctraveler.region.RegionProtection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Region protection for container use (inventory §2.8): a click inside an open
 * container is refused unless the region it was opened in allows it. Vanilla
 * resyncs the container to the client after the click either way, so a refused
 * click simply leaves everything where it was.
 *
 * <p>The player's own inventory (window id 0) is never a protected container,
 * exactly as in the Portal.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class RegionContainerClickMixin {

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void mctraveler$protectContainerClick(
            int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (menu.containerId == 0 || !(player instanceof ServerPlayer clicker)) {
            return;
        }
        if (!RegionProtection.allowsContainerUse(clicker)) {
            ci.cancel();
        }
    }
}
