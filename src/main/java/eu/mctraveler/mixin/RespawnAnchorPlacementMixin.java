package eu.mctraveler.mixin;

import eu.mctraveler.hooks.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Issue #88: a respawn anchor is Nucleus-built full, but breaking and
 * re-placing one loses that charge — vanilla never carries the {@code CHARGE}
 * block-state property into the dropped item, so a fresh one always starts
 * empty. {@link RespawnAnchorBlock} declares no {@code setPlacedBy} of its
 * own, so this hooks {@link Block}'s and lets everything but a respawn anchor
 * pass straight through; {@code eu.mctraveler.embassy.EmbassyAnchors} is where
 * "inside an embassy" is decided.
 */
@Mixin(Block.class)
public abstract class RespawnAnchorPlacementMixin {

    @Inject(method = "setPlacedBy", at = @At("TAIL"))
    private void mctraveler$rechargeEmbassyAnchor(
        Level level,
        BlockPos pos,
        BlockState state,
        LivingEntity placer,
        ItemStack stack,
        CallbackInfo ci
    ) {
        if ((Object) this instanceof RespawnAnchorBlock) {
            Hooks.respawnAnchorPlaced(level, pos, state);
        }
    }
}
