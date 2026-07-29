package eu.mctraveler.mixin;

import eu.mctraveler.region.RegionEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Region protection against mob griefing (spec User Story 35): the creatures
 * that break blocks cannot break a region's.
 *
 * <p>This one hook covers most of them, because it is the single method every
 * block a creature destroys goes through, and it carries the creature's name:
 * ravagers smashing crops, withers tunnelling, zombies breaking down doors,
 * villagers harvesting, rabbits raiding gardens, silverfish waking their
 * friends. Creepers arrive by way of their explosion instead, and endermen —
 * who move blocks rather than destroy them — have their own two hooks.
 *
 * <p>A destruction with no entity behind it is the world's own physics and is
 * left alone; a player's is {@link eu.mctraveler.region.RegionProtection}'s to
 * judge, because only that knows about membership and can say why.
 */
@Mixin(Level.class)
public abstract class RegionMobGriefingMixin {

    @Inject(
            method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void mctraveler$holdOffGriefingCreatures(
            BlockPos pos,
            boolean dropBlock,
            Entity breaker,
            int recursionLeft,
            CallbackInfoReturnable<Boolean> cir) {
        if (!RegionEnvironment.allowsCreatureBlockChange((Level) (Object) this, pos, breaker)) {
            cir.setReturnValue(false);
        }
    }
}
