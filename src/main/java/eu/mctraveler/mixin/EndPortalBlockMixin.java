package eu.mctraveler.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import eu.mctraveler.worlds.WorldRouting;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * End portals lead into the traveller's own trio (spec story 23), by the same
 * two nudges {@link NetherPortalBlockMixin} applies: read every dimension as
 * the vanilla trio's key for its role, and resolve the key vanilla settles on
 * inside the portal's own trio.
 *
 * <p>Reading the role matters twice over here, because vanilla's
 * {@code dimension() == Level.END} test decides the <em>direction</em> of
 * travel, not just the destination: untranslated, an end portal in Secondary's
 * End would read as an overworld portal and throw the player deeper into
 * Primary's End instead of home. The same test gates the end-credits sequence
 * on the way in, so translating it also gives Secondary's End the credits its
 * counterpart has always shown.
 *
 * <p>A player leaving an End is sent to their respawn point rather than a fixed
 * position; that path is World-scoped by
 * {@link ServerPlayerRespawnMixin} instead.
 */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {

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

    @ModifyExpressionValue(
        method = "entityInside",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;dimension()Lnet/minecraft/resources/ResourceKey;"
        )
    )
    private ResourceKey<Level> mctraveler$creditsRollInEveryEnd(ResourceKey<Level> dimension) {
        return WorldRouting.asVanillaTrio(dimension);
    }
}
