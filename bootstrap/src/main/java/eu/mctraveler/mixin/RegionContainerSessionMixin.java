package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The container session a region's protection is decided against (inventory
 * §2.8): opening one captures where the player stood, closing it releases the
 * capture. Everything the player then does inside that container is judged by
 * the region they opened it in, however far they wander meanwhile.
 *
 * <p>{@code initMenu} is the one place every kind of open funnels through —
 * blocks, horses, and the player's own inventory alike — so the inventory menu
 * is filtered out here; it is never a container in the Portal's sense (its
 * window id is 0, the id the Portal's own container hook skipped). A menu the
 * mod drew itself is filtered out for the same reason (spec deviation 16): it
 * stands in no region, so there is no region to judge it by. A workstation
 * (crafting table, grindstone, loom, …) is filtered out because its contents
 * are the player's own — a non-member may use one to the full (issue #40).
 */
@Mixin(ServerPlayer.class)
public abstract class RegionContainerSessionMixin {

    @Inject(method = "initMenu", at = @At("HEAD"))
    private void mctraveler$captureContainerRegion(AbstractContainerMenu menu, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (menu != player.inventoryMenu
                && !Hooks.isModOwnedMenu(menu)
                && !Hooks.isPersonalEnderChestMenu(menu)
                && !Hooks.isWorkstationMenu(menu)) {
            Hooks.containerOpened(player);
        }
    }

    @Inject(method = "doCloseContainer", at = @At("HEAD"))
    private void mctraveler$releaseContainerRegion(CallbackInfo ci) {
        Hooks.containerClosed((ServerPlayer) (Object) this);
    }
}
