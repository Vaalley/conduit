package eu.mctraveler.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Test-harness-only mixin (mod {@code mctraveler-test}, never shipped): the
 * vanilla gametest server bakes its world against an <em>empty</em> level-stem
 * registry, silently dropping every datapack-defined dimension — so the
 * Embassies dimension the production server loads from the mod jar would not
 * exist on the test server at all. This swaps in the datapack dimension registry
 * the surrounding {@link WorldLoader} already loaded, making the gametest server
 * honor datapacks exactly as a dedicated server does.
 *
 * <p>It was written for Secondary's trio, which shipped the same way until the
 * merge retired it; the Embassies (ADR 0003) are what still need it.
 *
 * <p>Targets the world-data supplier lambda inside
 * {@link GameTestServer#create}; the synthetic method name is stable for a
 * given Minecraft build but must be re-checked on Minecraft upgrades.
 */
@Mixin(GameTestServer.class)
abstract class GameTestServerDatapackDimensionsMixin {

    @WrapOperation(
        method = "lambda$create$1",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/WorldDimensions;bake(Lnet/minecraft/core/Registry;)Lnet/minecraft/world/level/levelgen/WorldDimensions$Complete;"
        )
    )
    private static WorldDimensions.Complete bakeWithDatapackDimensions(
        WorldDimensions presetDimensions,
        Registry<LevelStem> emptyRegistry,
        Operation<WorldDimensions.Complete> bake,
        @Local(argsOnly = true) WorldLoader.DataLoadContext context
    ) {
        Registry<LevelStem> datapackDimensions =
            context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM);
        return bake.call(presetDimensions, datapackDimensions);
    }
}
