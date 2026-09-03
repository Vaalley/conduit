package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Issue #52: a player marked away (idle five minutes, or {@code /away}) should not be
 * counted when the server works out how many players have to be asleep to skip the
 * night — otherwise one AFK player can hold the whole server awake.
 *
 * <p>{@link SleepStatus} does the maths off a {@code List<ServerPlayer>} handed in from
 * {@code ServerLevel}: {@code update} recomputes the active/sleeping tallies, and
 * {@code areEnoughDeepSleeping} re-scans for the day-skip check. Filtering the list at
 * the head of both drops away players from every count consistently; an away player is
 * never actually in a bed (climbing in is an interaction that clears the away flag), so
 * this only ever removes them from the denominator.
 *
 * <p>{@code require = 0}: this build ships no ProGuard mappings to verify the descriptors
 * against ({@code docs/dev-loop.md}); failing to apply just restores vanilla's count
 * rather than refusing the mod under {@code mctraveler.mixins.json}'s {@code defaultRequire}.
 */
@Mixin(SleepStatus.class)
public abstract class SleepRequirementMixin {

    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true, require = 0)
    private List<ServerPlayer> mctraveler$dropAwayFromUpdate(List<ServerPlayer> players) {
        return mctraveler$withoutAway(players);
    }

    @ModifyVariable(method = "areEnoughDeepSleeping", at = @At("HEAD"), argsOnly = true, require = 0)
    private List<ServerPlayer> mctraveler$dropAwayFromDeepSleeping(List<ServerPlayer> players) {
        return mctraveler$withoutAway(players);
    }

    private static List<ServerPlayer> mctraveler$withoutAway(List<ServerPlayer> players) {
        if (players.stream().noneMatch(Hooks::isAway)) {
            return players;
        }
        return players.stream().filter(player -> !Hooks.isAway(player)).toList();
    }
}
