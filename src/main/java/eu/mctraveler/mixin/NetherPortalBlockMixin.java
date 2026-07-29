package eu.mctraveler.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import eu.mctraveler.worlds.WorldRouting;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Nether portals lead into the traveller's own trio (spec story 23). Vanilla
 * picks the far side by comparing the portal's dimension against
 * {@code Level.NETHER} and asking the server for the one hardcoded counterpart,
 * which is Primary's whichever World the portal actually stands in.
 *
 * <p>Two nudges make that same code serve every World, rather than a rewrite of
 * the exit-portal search it guards: every {@code dimension()} it reads is
 * reported as the vanilla trio's key for that role, so its comparisons ask
 * "which end of the portal is this?" instead of "is this Primary?"; and the key
 * it settles on is resolved inside the portal's own trio on the way to
 * {@code getLevel}. Coordinate scaling is untouched — it reads the dimension
 * <em>type</em>, and Secondary's nether is a {@code minecraft:the_nether}.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {

    @ModifyExpressionValue(
        method = "getPortalDestination",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;"
        )
    )
    private ResourceKey<Level> mctraveler$readDimensionAsItsRole(ResourceKey<Level> dimension) {
        return WorldRouting.asVanillaTrio(dimension);
    }

    @ModifyArg(
        method = "getPortalDestination",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)"
                + "Lnet/minecraft/server/level/ServerLevel;"
        )
    )
    private ResourceKey<Level> mctraveler$stayInThisTrio(
        ResourceKey<Level> target,
        @Local(argsOnly = true) ServerLevel portalLevel
    ) {
        return WorldRouting.withinTrio(portalLevel.dimension(), target);
    }
}
