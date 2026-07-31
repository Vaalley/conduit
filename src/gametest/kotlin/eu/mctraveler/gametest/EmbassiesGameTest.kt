package eu.mctraveler.gametest

import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.region.RegionsFeature
import java.nio.file.Files
import java.nio.file.Path
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.block.Blocks

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

    // ---- the synthetic world region ----

    @GameTest
    fun theVoidBetweenPlotsIsTheSyntheticEmbassiesWorldRegion(helper: GameTestHelper) {
        val level = embassies(helper)
        val service = RegionsFeature.requireService()
        val region = checkNotNull(RegionsFeature.regionAt(level, BlockPos(1000, 0, 1000))) {
            "the embassies void resolved to no region at all"
        }

        helper.assertValueEqual(region.title, "Embassies World", "the region covering the embassies void")
        helper.assertTrue("NO_SCOREBOARD" in region.flags, "the world region does not fly NO_SCOREBOARD")
        helper.assertTrue(region.members.isEmpty(), "the world region has members; nobody may build in the void")
        helper.assertFalse(region in service.roots, "the world region was added to the live region tree")

        // Never persisted: it is not in the tree, so a save cannot carry it.
        service.save()
        helper.assertFalse(
            Files.readString(Path.of("regions.json")).contains("Embassies World"),
            "the synthetic world region was written to regions.json",
        )
        // The guard answers for this dimension alone.
        helper.assertTrue(
            RegionsFeature.regionAt(helper.level, BlockPos(1000, 0, 1000)) == null,
            "open ground outside the embassies dimension answered with a region",
        )
        helper.succeed()
    }

    @GameTest
    fun theVoidIsProtectedFromEveryoneLikeAnyRegionYouAreNotAMemberOf(helper: GameTestHelper) {
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T01VoidDig")
        val at = BlockPos(1100, 0, 1100)
        level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState())
        check(player.teleportTo(level, at.x + 0.5, 1.0, at.z + 1.5, emptySet(), 0.0f, 0.0f, false))

        helper.assertFalse(player.gameMode.destroyBlock(at), "a player dug up the protected embassies void")
        helper.assertValueEqual(
            player.messages.last(),
            protectedBy("Embassies World"),
            "the refusal in the embassies void",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun theSidebarIsHiddenInTheVoidBetweenPlots(helper: GameTestHelper) {
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T01VoidBar")
        createRegion(helper, player, 0.0 to 0.0, 3.0 to 2.0)
        val view = SidebarView(player)

        helper.runAfterDelay(2) {
            helper.assertTrue(view.refresh().visible, "no sidebar in the player's own region to begin with")
            check(player.teleportTo(level, 1200.5, 1.0, 1200.5, emptySet(), 0.0f, 0.0f, false))
        }
        helper.runAfterDelay(4) {
            view.refresh()
            helper.assertFalse(view.visible, "the sidebar showed in the embassies void")
            player.leave()
            helper.succeed()
        }
    }

    private fun embassies(helper: GameTestHelper): ServerLevel =
        checkNotNull(helper.level.server.getLevel(EmbassiesFeature.DIMENSION)) {
            "the ${EmbassiesFeature.DIMENSION.identifier()} dimension is not loaded on the server"
        }
}
