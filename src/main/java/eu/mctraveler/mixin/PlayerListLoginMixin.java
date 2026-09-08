package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListLoginMixin {
    @Inject(
        method = "canPlayerLogin(Ljava/net/SocketAddress;Lnet/minecraft/server/players/NameAndId;)Lnet/minecraft/network/chat/Component;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void mctraveler$denyBannedPlayer(
        SocketAddress address,
        NameAndId nameAndId,
        CallbackInfoReturnable<Component> cir
    ) {
        Component denial = Hooks.loginDenial(nameAndId);
        if (denial != null) cir.setReturnValue(denial);
    }
}
