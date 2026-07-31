package eu.mctraveler.gametest

import eu.mctraveler.embassy.EmbassiesFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.MobCategory

/**
 * The embassies dimension itself (spec stories 1-2): a flat void of plains
 * where nothing spawns, nothing rains, the sun never moves, and nothing can
 * hurt a player.
 *
 * The dimension is datapack JSON shipped in the mod jar, so what is asserted
 * here is what a dedicated server loads — the gametest server sees it through
 * `GameTestServerDatapackDimensionsMixin`, and `SmokeHook` proves the same
 * dimension on a real production boot.
 */
class EmbassiesGameTest {

    @GameTest
    fun theEmbassiesDimensionIsAFlatVoidFrozenAtNoon(helper: GameTestHelper) {
        val level = embassies(helper)
        val type = level.dimensionType()

        helper.assertTrue(
            type.hasFixedTime(),
            "the embassies dimension still keeps a time of day (deviation 2: it is frozen)",
        )
        helper.assertValueEqual(
            type.timelines().size(),
            0,
            "the number of timelines animating the embassies sky (nothing may move it)",
        )
        // Whatever a caller offers as the sun's angle, the dimension pins it to
        // noon (0 degrees) — the whole of "frozen daylight" a server can assert.
        helper.assertValueEqual(
            type.attributes().applyModifier(EnvironmentAttributes.SUN_ANGLE, 123.0f),
            0.0f,
            "the embassies sun's angle (deviation 2: fixed at noon)",
        )
        for (y in intArrayOf(-64, 0, 64, 200)) {
            helper.assertTrue(
                level.getBlockState(BlockPos(8, y, 8)).isAir,
                "the embassies dimension generated something other than air at y $y",
            )
        }
        helper.succeed()
    }

    @GameTest
    fun theEmbassiesBiomeIsAPlainsWithNoWeatherAndNoSpawns(helper: GameTestHelper) {
        val level = embassies(helper)
        val biome = level.getBiome(BlockPos(8, 0, 8))

        helper.assertTrue(
            biome.`is`(EmbassiesFeature.BIOME),
            "the embassies dimension's biome is ${biome.registeredName}, not ${EmbassiesFeature.BIOME.identifier()}",
        )
        helper.assertFalse(
            biome.value().hasPrecipitation(),
            "it rains in the embassies dimension (deviation 1: no weather)",
        )
        for (category in MobCategory.entries) {
            helper.assertTrue(
                biome.value().mobSettings.getMobs(category).isEmpty,
                "the embassies biome spawns $category mobs (deviation 1: no natural mobs)",
            )
        }
        helper.succeed()
    }

    private fun embassies(helper: GameTestHelper): ServerLevel =
        checkNotNull(helper.level.server.getLevel(EmbassiesFeature.DIMENSION)) {
            "the ${EmbassiesFeature.DIMENSION.identifier()} dimension is not loaded on the server"
        }
}
