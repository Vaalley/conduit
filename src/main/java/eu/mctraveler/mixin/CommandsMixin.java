package eu.mctraveler.mixin;

import com.mojang.brigadier.ParseResults;
import eu.mctraveler.away.AwayFeature;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Running a command counts as an interaction for the away system (Portal:
 * features/AwayFeature.ts). Fabric API has no command-execution event, so this
 * is one of the unavoidable mixins (ADR 0002). Injecting at HEAD means the
 * interaction is processed before the command itself executes — which is what
 * makes {@code /away} while away first return the player and then hit the
 * return cooldown, exactly as the Portal's hook ordering did.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"))
    private void mctraveler$countCommandAsInteraction(
            ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
        ServerPlayer player = parseResults.getContext().getSource().getPlayer();
        if (player != null) {
            AwayFeature.INSTANCE.onPlayerCommand(player);
        }
    }
}
