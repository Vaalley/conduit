package eu.mctraveler.gametest

import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.embassy.EmbassyOrigins
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import eu.mctraveler.worlds.WorldsFeature
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

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
        player.arriveIn(level, at.x + 0.5, 1.0, at.z + 1.5)

        helper.assertFalse(player.gameMode.destroyBlock(at), "a player dug up the protected embassies void")
        helper.assertValueEqual(
            player.messages.last(),
            protectedBy("Embassies World"),
            "the refusal in the embassies void",
        )
        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState()) // leave the void as it was found
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun noRegionCanBeCreatedInTheEmbassiesVoid(helper: GameTestHelper) {
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T01VoidRg")
        player.arriveIn(level, 1800.5, 1.0, 1800.5)

        player.runCommand("rg start")
        player.setPos(1810.5, 1.0, 1810.5)
        player.runCommand("rg end")

        // Nucleus turned this away the same way: the void is a region, and the
        // would-be creator is not a member of it.
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("You are not a member of the parent region"),
            "the refusal for /rg end in the embassies void",
        )
        player.leave()
        helper.succeed()
    }

    // ---- the dimension is in no World ----

    @GameTest
    fun embassiesBelongsToNoWorldSoTravelIgnoresIt(helper: GameTestHelper) {
        val worlds = checkNotNull(WorldsFeature.worlds) { "the Worlds service is not up" }
        helper.assertTrue(
            worlds.worldOf(EmbassiesFeature.DIMENSION) == null,
            "the embassies dimension was claimed by a World (ADR 0003: it is outside every trio)",
        )
        helper.assertValueEqual(
            RegionWorlds.legacyName(EmbassiesFeature.DIMENSION),
            "embassies",
            "the legacy world string embassy regions are stored under",
        )
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
            player.arriveIn(level, 1200.5, 1.0, 1200.5)
        }
        helper.runAfterDelay(4) {
            view.refresh()
            helper.assertFalse(view.visible, "the sidebar showed in the embassies void")
            player.leave()
            helper.succeed()
        }
    }

    // ---- damage ----

    @GameTest
    fun nothingHurtsAPlayerInsideTheEmbassiesDimension(helper: GameTestHelper) {
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T01Hurt")
        player.arriveIn(level, 1300.5, 1.0, 1300.5)
        player.health = 20.0f

        val sources = level.damageSources()
        for (source in listOf(sources.generic(), sources.fall(), sources.inFire(), sources.fellOutOfWorld())) {
            player.hurtServer(level, source, 5.0f)
            helper.assertValueEqual(
                player.health,
                20.0f,
                "the player's health after ${source.msgId} in the embassies dimension",
            )
        }
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun damageOutsideTheEmbassiesDimensionIsUntouched(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T01Hurt2")
        player.standAt(helper, 1.0, 1.0, 1.0)
        player.health = 20.0f

        player.hurtServer(helper.level, helper.level.damageSources().generic(), 5.0f)
        helper.assertValueEqual(player.health, 15.0f, "the player's health after ordinary damage outside embassies")
        player.leave()
        helper.succeed()
    }

    // ---- origins: the way in and the way out ----

    @GameTest
    fun fallingIntoTheVoidPutsThePlayerBackWhereTheyEnteredFrom(helper: GameTestHelper) {
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T01Fall")
        player.standAt(helper, 1.0, 1.0, 1.0)
        player.setYRot(45.0f)
        player.setXRot(10.0f)
        val origin = player.position()

        player.arriveIn(level, 1400.5, 1.0, 1400.5)
        // Off the edge of a plot: nothing below -64 but the void.
        player.setPos(1400.5, -70.0, 1400.5)
        player.fallDistance = 50.0

        helper.runAfterDelay(2) {
            helper.assertValueEqual(
                player.level().dimension(),
                helper.level.dimension(),
                "the dimension a fall into the void returns to",
            )
            helper.assertValueEqual(
                listOf(player.x, player.y, player.z),
                listOf(origin.x, origin.y, origin.z),
                "where a fall into the void puts the player back",
            )
            helper.assertValueEqual(player.yRot to player.xRot, 45.0f to 10.0f, "the rotation restored by the return")
            helper.assertValueEqual(player.fallDistance, 0.0, "the fall distance after the return")
            player.leave()
            helper.succeed()
        }
    }

    @GameTest
    fun leavingTheDimensionByTeleportDropsTheOrigin(helper: GameTestHelper) {
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T01Leave")
        player.standAt(helper, 1.0, 1.0, 1.0)

        player.arriveIn(level, 1900.5, 1.0, 1900.5)
        helper.assertTrue(EmbassyOrigins.originOf(player) != null, "entering embassies recorded no origin")

        // Out again by an ordinary teleport — the crystal menu and the embassy
        // anchor both leave this way (deviation 11).
        val out = helper.absoluteVec(Vec3(1.0, 1.0, 1.0))
        player.arriveIn(helper.level, out.x, out.y, out.z)
        helper.assertTrue(
            EmbassyOrigins.originOf(player) == null,
            "the origin outlived the player's teleport out of embassies",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun aPlayerWithNoRecordedOriginIsLeftAloneInTheVoid(helper: GameTestHelper) {
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T01FallLost")
        player.arriveIn(level, 1500.5, 1.0, 1500.5)
        // A player the server never saw arrive — one who was inside when it
        // last went down (ADR 0003). Nucleus left them where they were.
        EmbassyOrigins.forget(player.uuid)
        player.setPos(1500.5, -70.0, 1500.5)

        helper.runAfterDelay(2) {
            helper.assertValueEqual(
                player.level().dimension(),
                EmbassiesFeature.DIMENSION,
                "the dimension of a player with no origin who fell into the void",
            )
            helper.assertValueEqual(player.y, -70.0, "where a player with no origin ends up")
            player.leave()
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 600)
    fun loggingOutInsideEmbassiesLogsBackInAtTheOrigin(helper: GameTestHelper) {
        val server = helper.level.server
        val level = embassies(helper)
        val uuid = UUID.randomUUID()

        val firstSession = TestPlayers.login(server, "T01Relog", uuid)
        check(firstSession.teleportTo(server.overworld(), 900.5, 80.0, 900.5, emptySet(), 90.0f, 5.0f, false))
        firstSession.arriveIn(level, 1600.5, 1.0, 1600.5)
        TestPlayers.logout(firstSession)

        val secondSession = TestPlayers.login(server, "T01Relog", uuid)
        try {
            helper.assertValueEqual(
                secondSession.level().dimension(),
                Level.OVERWORLD,
                "the dimension a player who logged out inside embassies logs back into",
            )
            helper.assertValueEqual(
                listOf(secondSession.x, secondSession.y, secondSession.z),
                listOf(900.5, 80.0, 900.5),
                "where a player who logged out inside embassies logs back in",
            )
        } finally {
            TestPlayers.logout(secondSession)
        }
        helper.succeed()
    }

    // Its own test environment, which is to say its own batch: this is the one
    // test that reaches for every player on the server, and tests within a batch
    // run side by side — it would send its neighbours' players home mid-assertion.
    //
    // The environment *id* is what buys that, not the file's contents: batches
    // are `groupingBy` the environment a test names, so every own-batch test
    // needs an id of its own. The `own_batch_*` files are deliberately identical
    // and deliberately not shared — merging them would put these tests back in
    // one batch, which is the very thing each of them cannot survive.
    @GameTest(environment = "mctraveler-test:own_batch_embassies_stop")
    fun theServerGoingDownReturnsEveryoneStillInside(helper: GameTestHelper) {
        val server = helper.level.server
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T01Stop")
        player.standAt(helper, 1.0, 1.0, 1.0)
        val origin = player.position()
        player.arriveIn(level, 1700.5, 1.0, 1700.5)

        EmbassiesFeature.returnEveryoneInside(server)

        helper.assertValueEqual(
            player.level().dimension(),
            helper.level.dimension(),
            "the dimension a stopping server leaves the player in",
        )
        helper.assertValueEqual(
            listOf(player.x, player.y, player.z),
            listOf(origin.x, origin.y, origin.z),
            "where a stopping server leaves the player",
        )
        player.leave()
        helper.succeed()
    }

    private fun embassies(helper: GameTestHelper): ServerLevel =
        checkNotNull(helper.level.server.getLevel(EmbassiesFeature.DIMENSION)) {
            "the ${EmbassiesFeature.DIMENSION.identifier()} dimension is not loaded on the server"
        }
}
