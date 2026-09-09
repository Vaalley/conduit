package eu.mctraveler.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the vanilla dragon fight's exit portal location for egg recovery. */
@Mixin(EnderDragonFight.class)
public interface EnderDragonFightAccessor {
    @Accessor("exitPortalLocation")
    BlockPos mctraveler$exitPortalLocation();
}
