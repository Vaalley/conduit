package eu.mctraveler.mixin;

import com.mojang.datafixers.util.Either;
import eu.mctraveler.region.RegionStrawBeds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.StrawBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records who last successfully started sleeping in a straw bed, and where —
 * {@code destroyOnLeave} (the vanilla self-destruct a straw bed fires when
 * its sleeper gets up) has no player parameter of its own to check against a
 * region with, so {@link StrawBedProtectionMixin} looks this back up instead.
 * See {@link RegionStrawBeds} for why.
 */
@Mixin(Player.class)
public abstract class StrawBedSleepTrackingMixin {

    @Inject(method = "startSleepInBed", at = @At("RETURN"))
    private void mctraveler$recordStrawBedSleep(
        AbstractBedBlock block,
        BlockState state,
        BedRule bedRule,
        BlockPos pos,
        CallbackInfoReturnable<Either<Player.BedSleepingProblem, net.minecraft.util.Unit>> cir
    ) {
        if (!(block instanceof StrawBedBlock)) return;
        if (!(((Object) this) instanceof ServerPlayer player)) return;
        if (cir.getReturnValue().right().isPresent()) {
            RegionStrawBeds.recordSleep(pos, player);
        }
    }
}
