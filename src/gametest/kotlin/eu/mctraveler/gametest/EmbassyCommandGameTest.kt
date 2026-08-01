package eu.mctraveler.gametest

import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.embassy.EmbassyOrigins
import eu.mctraveler.embassy.EmbassyPlots
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks

/**
 * The `/embassy` command family: the two-line help, `create`'s allocation and
 * build, and `delete`'s guard ladder, confirmation and teardown (spec stories
 * 8, 9, 11, 16, 17, 18).
 *
 * Which plot a `create` lands on depends on what the rest of the suite has
 * already claimed, so nothing here asserts a plot by name — the tests read back
 * where the player was put and check the embassy from there.
 */
class EmbassyCommandGameTest {

    @GameTest
    fun bareEmbassyPrintsItsTwoSubcommands(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T02Help")
        player.runCommand("embassy")

        // Nucleus sent two plain lines, with no prefix and no styling. Picked
        // out of the message list rather than counted: a captured player also
        // hears the server's broadcasts, including other tests' joins.
        val lines = player.messages.filter { it.string.startsWith("/embassy") }
        helper.assertValueEqual(
            lines.map(Component::getString),
            listOf("/embassy create", "/embassy delete"),
            "the /embassy help lines",
        )
        helper.assertTrue(lines[0].style.isEmpty, "the help lines should carry no styling")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun creatingAnEmbassyIsForAdminsOnly(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T02CrGate")
        player.runCommand("embassy create")
        helper.assertValueEqual(player.messages.last(), notAdmin, "the non-admin /embassy create reply")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun deletingAnEmbassyIsForAdminsOnly(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T02DelGate")
        player.runCommand("embassy delete")
        helper.assertValueEqual(player.messages.last(), notAdmin, "the non-admin /embassy delete reply")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun creatingAnEmbassyFromInsideEmbassiesIsRefused(helper: GameTestHelper) {
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T02CrIn")
        player.makeAdmin()
        player.arriveIn(level, 2100.5, 1.0, 2100.5)

        player.runCommand("embassy create")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("You must not be in the embassies world"),
            "the /embassy create reply from inside embassies",
        )
        EmbassyOrigins.forget(player.uuid)
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun creatingAnEmbassyBuildsThePlotAndTakesYouThere(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T02Create")
        player.makeAdmin()
        player.standAt(helper, 1.0, 1.0, 1.0)
        val fromX = player.x
        val fromY = player.y
        val fromZ = player.z

        player.runCommand("embassy create")

        helper.assertValueEqual(
            player.messages.last(),
            Paint.success("Created embassy"),
            "the /embassy create reply",
        )
        helper.assertValueEqual(
            player.level().dimension(),
            EmbassiesFeature.DIMENSION,
            "the dimension /embassy create leaves you in",
        )

        // Dropped in the middle of the plot it just built.
        val plot = EmbassyPlots.plotOf(player.blockX, player.blockZ)
        helper.assertValueEqual(
            listOf(player.x, player.y, player.z),
            listOf(plot.x * 16 + 8.5, 1.0, plot.z * 16 + 8.5),
            "where /embassy create leaves you",
        )
        val level = embassies(helper)
        helper.assertValueEqual(
            level.getBlockState(BlockPos(plot.x * 16 + 8, 0, plot.z * 16 + 8)).block,
            Blocks.RESPAWN_ANCHOR,
            "the anchor of the plot /embassy create built",
        )

        // The region, exactly as story 11 asks for it.
        val region = checkNotNull(
            RegionsFeature.regionAt(RegionWorlds.EMBASSIES, plot.x * 16 + 8, 0, plot.z * 16 + 8),
        ) { "no region was created over the new plot" }
        helper.assertValueEqual(region.title, "Unnamed Embassy", "the new embassy's title")
        helper.assertValueEqual(region.world, RegionWorlds.EMBASSIES, "the new embassy's world")
        helper.assertValueEqual(region.startX, plot.x * 16 + 3, "the new embassy's start-x")
        helper.assertValueEqual(region.startZ, plot.z * 16 + 3, "the new embassy's start-z")
        helper.assertValueEqual(region.endX, plot.x * 16 + 13, "the new embassy's end-x")
        helper.assertValueEqual(region.endZ, plot.z * 16 + 13, "the new embassy's end-z")
        helper.assertValueEqual(region.startY, 320, "the new embassy's start-y")
        helper.assertValueEqual(region.endY, -64, "the new embassy's end-y")
        helper.assertValueEqual(region.members.toList(), listOf(player.uuid), "the new embassy's members")
        helper.assertValueEqual(region.flags.toList(), listOf("EMBASSY"), "the new embassy's flags")

        // ...and the destination it remembers is where the creator was standing.
        val destination = checkNotNull(region.metadata["embassy-destination"]) {
            "the new embassy recorded no destination"
        }.asJsonObject
        helper.assertValueEqual(destination.get("x").asDouble, fromX, "the recorded destination x")
        helper.assertValueEqual(destination.get("y").asDouble, fromY, "the recorded destination y")
        helper.assertValueEqual(destination.get("z").asDouble, fromZ, "the recorded destination z")
        helper.assertValueEqual(destination.get("world").asString, "world", "the recorded destination world")

        // And entering embassies recorded an origin, with no help from the command.
        helper.assertTrue(
            EmbassyOrigins.originOf(player) != null,
            "/embassy create should have recorded an origin on the way in",
        )

        cleanUp(helper, region, plot)
        player.leave()
        helper.succeed()
    }

    // ---- delete's guard ladder (story 16) ----

    @GameTest
    fun deletingFromOutsideEmbassiesIsRefused(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T02DelOut")
        player.makeAdmin()
        player.standAt(helper, 1.0, 1.0, 1.0)

        player.runCommand("embassy delete")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("You must be in the embassies world"),
            "the /embassy delete reply from outside embassies",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun deletingFromTheVoidBetweenPlotsIsRefused(helper: GameTestHelper) {
        // The void is a region too — the synthetic world one — so this proves
        // the guard tests the EMBASSY flag rather than "a region exists here".
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T02DelVoid")
        player.makeAdmin()
        player.arriveIn(level, 2200.5, 1.0, 2200.5)

        player.runCommand("embassy delete")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("You must be in an embassy"),
            "the /embassy delete reply from the embassies void",
        )
        EmbassyOrigins.forget(player.uuid)
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun deletingAnEmbassyYouAreNotAMemberOfIsRefused(helper: GameTestHelper) {
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T02DelOther")
        player.makeAdmin()
        val plot = ChunkPos(600, 600)
        val region = embassyOver(plot, "Someone Else's Embassy", owner = null)
        RegionsFeature.requireService().add(region, parent = null)
        player.arriveIn(level, plot.x * 16 + 8.5, 1.0, plot.z * 16 + 8.5)

        player.runCommand("embassy delete")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("You are not a member of this embassy"),
            "the /embassy delete reply for a non-member",
        )
        RegionsFeature.requireService().remove(region)
        EmbassyOrigins.forget(player.uuid)
        player.leave()
        helper.succeed()
    }

    // ---- delete's confirmation and teardown (stories 17, 18) ----

    @GameTest
    fun deletingWithoutTheTitleAsksForAClickableConfirmation(helper: GameTestHelper) {
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T02DelAsk")
        player.makeAdmin()
        val plot = ChunkPos(610, 610)
        val region = embassyOver(plot, "Marble Hall", owner = player.uuid)
        RegionsFeature.requireService().add(region, parent = null)
        player.arriveIn(level, plot.x * 16 + 8.5, 1.0, plot.z * 16 + 8.5)

        player.runCommand("embassy delete")
        val warning = player.messages.last()
        helper.assertValueEqual(
            warning.string,
            "WARNING Are you sure you want to delete this embassy? The embassy build will also be " +
                "deleted. Click here to confirm. This cannot be undone.",
            "the /embassy delete confirmation text",
        )
        val here = runFor(warning, "here")
        helper.assertValueEqual(
            checkNotNull(here.style.color?.serialize()) { "the confirmation's \"here\" had no colour" },
            "gold",
            "the confirmation's \"here\" colour",
        )
        helper.assertValueEqual(
            checkNotNull(here.style.clickEvent) { "the confirmation's \"here\" was not clickable" },
            ClickEvent.RunCommand("/embassy delete Marble Hall"),
            "the confirmation's \"here\" click event",
        )

        // A wrong title is not a confirmation either.
        player.runCommand("embassy delete Marble")
        helper.assertTrue(
            player.messages.last().string.startsWith("WARNING Are you sure"),
            "a mistyped title should ask again rather than delete",
        )
        helper.assertTrue(
            RegionsFeature.regionAt(RegionWorlds.EMBASSIES, plot.x * 16 + 8, 0, plot.z * 16 + 8) === region,
            "the embassy should still be there after a mistyped title",
        )

        RegionsFeature.requireService().remove(region)
        EmbassyOrigins.forget(player.uuid)
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun deletingWithTheExactTitleClearsThePlotAndTheRegion(helper: GameTestHelper) {
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T02DelDo")
        player.makeAdmin()
        val plot = ChunkPos(620, 620)
        EmbassyPlots.populate(level, plot)
        val region = embassyOver(plot, "Glass House", owner = player.uuid)
        RegionsFeature.requireService().add(region, parent = null)
        player.arriveIn(level, plot.x * 16 + 8.5, 1.0, plot.z * 16 + 8.5)

        player.runCommand("embassy delete Glass House")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.success("Embassy deleted"),
            "the /embassy delete reply",
        )
        helper.assertTrue(
            RegionsFeature.regionAt(RegionWorlds.EMBASSIES, plot.x * 16 + 8, 0, plot.z * 16 + 8)
                === EmbassiesFeature.worldRegion,
            "the deleted embassy's ground should be void again",
        )
        helper.assertFalse(
            RegionsFeature.requireService().roots.contains(region),
            "the deleted embassy should be out of the region tree",
        )
        for (y in listOf(level.minY, -64, 0, level.maxY)) {
            helper.assertTrue(
                level.getBlockState(BlockPos(plot.x * 16 + 8, y, plot.z * 16 + 8)).isAir,
                "the deleted embassy's build should be gone at y=$y",
            )
        }
        EmbassyOrigins.forget(player.uuid)
        player.leave()
        helper.succeed()
    }

    // ---- helpers ----

    private val notAdmin = Paint.error("You must be an admin to use this command")

    private fun embassies(helper: GameTestHelper): ServerLevel =
        checkNotNull(helper.level.server.getLevel(EmbassiesFeature.DIMENSION)) {
            "the ${EmbassiesFeature.DIMENSION.identifier()} dimension is not loaded on the server"
        }

    /** The rendered run whose text is [text], with the style the client resolves. */
    private fun runFor(component: Component, text: String): Component =
        component.toFlatList(component.style).first { it.string == text }

    private fun embassyOver(plot: ChunkPos, title: String, owner: java.util.UUID?): Region =
        Region(
            title = title,
            world = RegionWorlds.EMBASSIES,
            startX = plot.x * 16 + EmbassyPlots.GRASS_MIN,
            startZ = plot.z * 16 + EmbassyPlots.GRASS_MIN,
            endX = plot.x * 16 + EmbassyPlots.GRASS_MAX,
            endZ = plot.z * 16 + EmbassyPlots.GRASS_MAX,
        ).also { region ->
            region.flags.add("EMBASSY")
            owner?.let(region.members::add)
        }

    private fun cleanUp(helper: GameTestHelper, region: Region, plot: ChunkPos) {
        RegionsFeature.requireService().remove(region)
        EmbassyPlots.clear(embassies(helper), plot)
    }
}
