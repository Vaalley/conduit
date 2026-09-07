package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Protects farmland conversion without intercepting {@code fallOn} itself, so
 * vanilla fall damage and every other fall-on behavior remain unchanged.
 */
@Mixin(FarmlandBlock.class)
public abstract class RegionFarmlandMixin {

    @Shadow
    private static void turnToDirt(Entity entity, BlockState state, Level level, BlockPos pos) {
        throw new AssertionError();
    }

    @Redirect(
            method = "fallOn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/FarmlandBlock;turnToDirt(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void mctraveler$protectFarmlandConversion(
            Entity entity, BlockState state, Level level, BlockPos pos) {
        if (level.isClientSide() || entity == null) {
            turnToDirt(entity, state, level, pos);
        } else if (entity instanceof ServerPlayer player) {
            if (Hooks.allowsBlockChange(player, level, pos)) {
                turnToDirt(entity, state, level, pos);
            }
        } else if (entity instanceof Player) {
            turnToDirt(entity, state, level, pos);
        } else if (Hooks.allowsCreatureBlockChange(level, pos, entity)) {
            turnToDirt(entity, state, level, pos);
    }
}
}
