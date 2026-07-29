package eu.mctraveler.mixin;

import eu.mctraveler.region.RegionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The half of {@code DISABLE_PUBLIC_REDSTONE_TRIGGERS} that is not a
 * right-click (spec User Story 36): a pressure plate is a trigger a stranger
 * works with their feet, so it is stepping on one — not clicking it — that has
 * to be refused.
 *
 * <p>Only players are judged; a mob or a dropped item still weighs on a plate
 * exactly as before, since only a player can be a member of anything.
 */
@Mixin(BasePressurePlateBlock.class)
public abstract class RegionPressurePlateMixin {

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void mctraveler$keepStrangersOffThePlate(
            BlockState state,
            Level level,
            BlockPos pos,
            Entity entity,
            InsideBlockEffectApplier effectApplier,
            boolean isPrecise,
            CallbackInfo ci) {
        if (entity instanceof ServerPlayer player && !RegionProtection.allowsPressurePlate(player, level, pos)) {
            ci.cancel();
        }
    }
}
