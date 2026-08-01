package eu.mctraveler.gametest

import com.google.gson.JsonObject
import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.embassy.EmbassyAnchors
import eu.mctraveler.embassy.EmbassyOrigins
import eu.mctraveler.embassy.EmbassyPlots
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * The embassy anchor: the teleporter you step onto (spec stories 13-14) and the
 * guard that keeps it from being blown up (story 15).
 *
 * The dimension turns respawn anchors off (`respawn_anchor_works: false`), so
 * vanilla's answer to a right-click on a charged one is an explosion — which is
 * exactly what the guard tests here assert never happens.
 */
class EmbassyAnchorGameTest {

    @GameTest(maxTicks = 200)
    fun steppingOntoTheAnchorSendsYouToTheEmbassysDestination(helper: GameTestHelper) {
        val level = embassies(helper)
        val plot = ChunkPos(700, 700)
        val player = MessageCapturingPlayer.join(helper, "T02Step")
        val region = embassyOn(level, plot, "Step Hall", player.uuid, destination(90.0f, 5.0f))

        // Arrive beside the anchor: the first tick in the dimension only
        // establishes where the player is, so the trip needs a real step.
        player.arriveIn(level, plot.x * 16 + 6.5, 1.0, plot.z * 16 + 8.5)
        helper.runAfterDelay(2) {
            player.setPos(plot.x * 16 + 8.5, 1.0, plot.z * 16 + 8.5)
        }
        helper.runAfterDelay(4) {
            helper.assertValueEqual(
                player.saying("SUCCESS Teleported from embassy"),
                Paint.success("Teleported from embassy"),
                "the anchor teleport reply",
            )
            helper.assertValueEqual(
                player.level().dimension(),
                Level.OVERWORLD,
                "the dimension the anchor sent the player to",
            )
            helper.assertValueEqual(
                listOf(player.x, player.y, player.z),
                listOf(950.5, 80.0, 950.5),
                "where the anchor sent the player",
            )
            helper.assertValueEqual(player.yRot, 90.0f, "the yaw the anchor restored")
            helper.assertValueEqual(player.xRot, 5.0f, "the pitch the anchor restored")

            cleanUp(helper, region, plot, player)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 200)
    fun sneakingOverTheAnchorStaysPutAndSaysSo(helper: GameTestHelper) {
        val level = embassies(helper)
        val plot = ChunkPos(710, 710)
        val player = MessageCapturingPlayer.join(helper, "T02Sneak")
        val region = embassyOn(level, plot, "Sneak Hall", player.uuid, destination())
        player.isShiftKeyDown = true
        player.arriveIn(level, plot.x * 16 + 6.5, 1.0, plot.z * 16 + 8.5)

        helper.runAfterDelay(2) { player.setPos(plot.x * 16 + 8.5, 1.0, plot.z * 16 + 8.5) }
        helper.runAfterDelay(4) {
            helper.assertValueEqual(
                player.saying("INFO Sneaking, teleportation ignored"),
                Paint.info("Sneaking, teleportation ignored"),
                "the sneaking-over-the-anchor reply",
            )
            helper.assertValueEqual(
                player.level().dimension(),
                EmbassiesFeature.DIMENSION,
                "a sneaking player should not have been teleported",
            )
            val said = player.sneakNotices()

            // Standing still on it says nothing more...
            helper.runAfterDelay(2) {
                helper.assertValueEqual(player.sneakNotices(), said, "standing still should not repeat the notice")
                // ...but stepping off and back on is a new step onto the anchor.
                player.setPos(plot.x * 16 + 6.5, 1.0, plot.z * 16 + 8.5)
                helper.runAfterDelay(2) {
                    player.setPos(plot.x * 16 + 8.5, 1.0, plot.z * 16 + 8.5)
                    helper.runAfterDelay(2) {
                        helper.assertValueEqual(
                            player.sneakNotices(),
                            said + 1,
                            "stepping back onto the anchor should say it again",
                        )
                        cleanUp(helper, region, plot, player)
                        helper.succeed()
                    }
                }
            }
        }
    }

    @GameTest(maxTicks = 200)
    fun anAdminIsOfferedAClickableWayBack(helper: GameTestHelper) {
        val level = embassies(helper)
        val plot = ChunkPos(720, 720)
        val player = MessageCapturingPlayer.join(helper, "T02Back")
        player.makeAdmin()
        val region = embassyOn(level, plot, "Back Hall", player.uuid, destination())
        player.arriveIn(level, plot.x * 16 + 6.5, 1.0, plot.z * 16 + 8.5)

        helper.runAfterDelay(2) { player.setPos(plot.x * 16 + 8.5, 1.0, plot.z * 16 + 8.5) }
        helper.runAfterDelay(4) {
            val back = checkNotNull(player.messages.firstOrNull { it.string.startsWith("INFO You can click") }) {
                "the admin was offered no way back"
            }
            helper.assertValueEqual(
                back.string,
                "INFO You can click here to go back to your previous location.",
                "the admin back-link text",
            )
            // Nucleus offered the way back before the trip, so it comes first.
            helper.assertTrue(
                player.messages.indexOf(back) <
                    player.messages.indexOfFirst { it.string == "SUCCESS Teleported from embassy" },
                "the way back should be offered before the teleport",
            )
            val expected = "/execute in mctraveler:embassies run tp @s " +
                "${plot.x * 16 + 8} 1 ${plot.z * 16 + 8}"
            helper.assertValueEqual(
                checkNotNull(runFor(back, "here").style.clickEvent) { "the back-link was not clickable" },
                ClickEvent.RunCommand(expected),
                "the admin back-link click event",
            )
            helper.assertValueEqual(
                checkNotNull(runFor(back, "here").style.color?.serialize()) { "\"here\" had no colour" },
                "aqua",
                "the back-link's \"here\" colour",
            )
            cleanUp(helper, region, plot, player)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 200)
    fun anOrdinaryPlayerIsOfferedNoWayBack(helper: GameTestHelper) {
        val level = embassies(helper)
        val plot = ChunkPos(730, 730)
        val player = MessageCapturingPlayer.join(helper, "T02NoBack")
        val region = embassyOn(level, plot, "Plain Hall", player.uuid, destination())
        player.arriveIn(level, plot.x * 16 + 6.5, 1.0, plot.z * 16 + 8.5)

        helper.runAfterDelay(2) { player.setPos(plot.x * 16 + 8.5, 1.0, plot.z * 16 + 8.5) }
        helper.runAfterDelay(4) {
            helper.assertValueEqual(
                player.saying("SUCCESS Teleported from embassy"),
                Paint.success("Teleported from embassy"),
                "the anchor teleport reply",
            )
            helper.assertTrue(
                player.messages.none { it.string.startsWith("INFO You can click") },
                "a player who is not an admin should be offered no way back",
            )
            cleanUp(helper, region, plot, player)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 200)
    fun anEmbassyWithNoRecordedDestinationDoesNothing(helper: GameTestHelper) {
        val level = embassies(helper)
        val plot = ChunkPos(740, 740)
        val player = MessageCapturingPlayer.join(helper, "T02NoDest")
        // Imported data can be missing its destination, and a world string this
        // server has never heard of is just as good as nothing.
        val region = embassyOn(level, plot, "Empty Hall", player.uuid, destination(world = "atlantis"))
        player.arriveIn(level, plot.x * 16 + 6.5, 1.0, plot.z * 16 + 8.5)

        helper.runAfterDelay(2) { player.setPos(plot.x * 16 + 8.5, 1.0, plot.z * 16 + 8.5) }
        helper.runAfterDelay(4) {
            helper.assertTrue(
                player.messages.none { it.string == "SUCCESS Teleported from embassy" },
                "an unusable destination should say nothing",
            )
            helper.assertValueEqual(
                player.level().dimension(),
                EmbassiesFeature.DIMENSION,
                "an unusable destination should move nobody",
            )
            cleanUp(helper, region, plot, player)
            helper.succeed()
        }
    }

    // ---- the right-click guard (story 15) ----

    @GameTest
    fun anOwnerCannotBlowUpTheirOwnAnchor(helper: GameTestHelper) {
        // The path region protection cannot close: an owner is a member, so
        // protection lets them through and only this guard stands in the way.
        val level = embassies(helper)
        val plot = ChunkPos(750, 750)
        val player = MessageCapturingPlayer.join(helper, "T02Boom1")
        val region = embassyOn(level, plot, "Owner Hall", player.uuid, destination())
        val anchor = anchorOf(plot)
        player.arriveIn(level, plot.x * 16 + 6.5, 1.0, plot.z * 16 + 8.5)

        helper.assertFalse(
            EmbassyAnchors.allowsAnchorUse(player, level, anchor),
            "an owner should not be able to set off their own anchor",
        )
        rightClicks(player, level, anchor, ItemStack.EMPTY)
        assertAnchorIntact(helper, level, anchor, "after its owner right-clicked it")

        cleanUp(helper, region, plot, player)
        helper.succeed()
    }

    @GameTest
    fun aVisitorCannotBlowUpSomeoneElsesAnchor(helper: GameTestHelper) {
        val level = embassies(helper)
        val plot = ChunkPos(760, 760)
        val player = MessageCapturingPlayer.join(helper, "T02Boom2")
        val region = embassyOn(level, plot, "Visitor Hall", UUID.randomUUID(), destination())
        val anchor = anchorOf(plot)
        player.arriveIn(level, plot.x * 16 + 6.5, 1.0, plot.z * 16 + 8.5)

        helper.assertFalse(
            EmbassyAnchors.allowsAnchorUse(player, level, anchor),
            "a visitor should not be able to set off an anchor",
        )
        rightClicks(player, level, anchor, ItemStack.EMPTY)
        assertAnchorIntact(helper, level, anchor, "after a visitor right-clicked it")

        cleanUp(helper, region, plot, player)
        helper.succeed()
    }

    @GameTest
    fun glowstoneStillTopsUpAnUnderChargedAnchor(helper: GameTestHelper) {
        val level = embassies(helper)
        val plot = ChunkPos(770, 770)
        val player = MessageCapturingPlayer.join(helper, "T02Charge")
        val region = embassyOn(level, plot, "Charge Hall", player.uuid, destination())
        val anchor = anchorOf(plot)
        level.setBlockAndUpdate(
            anchor,
            Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 2),
        )
        player.arriveIn(level, plot.x * 16 + 6.5, 1.0, plot.z * 16 + 8.5)

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.GLOWSTONE))
        helper.assertTrue(
            EmbassyAnchors.allowsAnchorUse(player, level, anchor),
            "recharging an under-charged anchor should stay possible",
        )
        rightClicks(player, level, anchor, player.mainHandItem)
        helper.assertValueEqual(
            level.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE),
            3,
            "the charges after topping the anchor up with glowstone",
        )

        cleanUp(helper, region, plot, player)
        helper.succeed()
    }

    @GameTest
    fun glowstoneOnAFullAnchorIsRefused(helper: GameTestHelper) {
        val level = embassies(helper)
        val plot = ChunkPos(780, 780)
        val player = MessageCapturingPlayer.join(helper, "T02Full")
        val region = embassyOn(level, plot, "Full Hall", player.uuid, destination())
        val anchor = anchorOf(plot)
        player.arriveIn(level, plot.x * 16 + 6.5, 1.0, plot.z * 16 + 8.5)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.GLOWSTONE))

        helper.assertFalse(
            EmbassyAnchors.allowsAnchorUse(player, level, anchor),
            "a full anchor has nothing to top up",
        )
        rightClicks(player, level, anchor, player.mainHandItem)
        assertAnchorIntact(helper, level, anchor, "after glowstone on a full anchor")

        cleanUp(helper, region, plot, player)
        helper.succeed()
    }

    @GameTest
    fun anEmptyAnchorIsLeftAlone(helper: GameTestHelper) {
        val level = embassies(helper)
        val plot = ChunkPos(790, 790)
        val player = MessageCapturingPlayer.join(helper, "T02Empty")
        val region = embassyOn(level, plot, "Empty Anchor Hall", player.uuid, destination())
        val anchor = anchorOf(plot)
        level.setBlockAndUpdate(
            anchor,
            Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 0),
        )
        player.arriveIn(level, plot.x * 16 + 6.5, 1.0, plot.z * 16 + 8.5)

        helper.assertTrue(
            EmbassyAnchors.allowsAnchorUse(player, level, anchor),
            "an anchor with no charges has nothing to guard",
        )
        cleanUp(helper, region, plot, player)
        helper.succeed()
    }

    @GameTest
    fun anAnchorOutsideAnEmbassyIsNotGuarded(helper: GameTestHelper) {
        // Deviation 17: the guard is scoped to embassy-flagged regions, so an
        // anchor in the void is vanilla's business, not ours.
        val level = embassies(helper)
        val player = MessageCapturingPlayer.join(helper, "T02Void")
        val anchor = BlockPos(2300, 0, 2300)
        level.setBlockAndUpdate(
            anchor,
            Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 4),
        )
        player.arriveIn(level, 2300.5, 1.0, 2302.5)

        helper.assertTrue(
            EmbassyAnchors.allowsAnchorUse(player, level, anchor),
            "the guard should only speak for anchors inside an embassy",
        )
        level.setBlockAndUpdate(anchor, Blocks.AIR.defaultBlockState())
        EmbassyOrigins.forget(player.uuid)
        player.leave()
        helper.succeed()
    }

    // ---- helpers ----

    private fun embassies(helper: GameTestHelper): ServerLevel =
        checkNotNull(helper.level.server.getLevel(EmbassiesFeature.DIMENSION)) {
            "the ${EmbassiesFeature.DIMENSION.identifier()} dimension is not loaded on the server"
        }

    private fun anchorOf(plot: ChunkPos): BlockPos =
        BlockPos(plot.x * 16 + EmbassyPlots.ANCHOR_LOCAL, EmbassyPlots.FLOOR_Y, plot.z * 16 + EmbassyPlots.ANCHOR_LOCAL)

    /** A destination in the overworld, in the shape `/embassy create` records. */
    private fun destination(yaw: Float = 0.0f, pitch: Float = 0.0f, world: String = "world"): JsonObject =
        JsonObject().apply {
            addProperty("x", 950.5)
            addProperty("y", 80.0)
            addProperty("z", 950.5)
            addProperty("yaw", yaw)
            addProperty("pitch", pitch)
            addProperty("world", world)
        }

    /** Builds a plot and registers the embassy region over it. */
    private fun embassyOn(
        level: ServerLevel,
        plot: ChunkPos,
        title: String,
        owner: UUID,
        destination: JsonObject,
    ): Region {
        EmbassyPlots.populate(level, plot)
        val region = Region(
            title = title,
            world = RegionWorlds.EMBASSIES,
            startX = plot.x * 16 + EmbassyPlots.GRASS_MIN,
            startZ = plot.z * 16 + EmbassyPlots.GRASS_MIN,
            endX = plot.x * 16 + EmbassyPlots.GRASS_MAX,
            endZ = plot.z * 16 + EmbassyPlots.GRASS_MAX,
        )
        region.flags.add("EMBASSY")
        region.members.add(owner)
        region.metadata["embassy-destination"] = destination
        RegionsFeature.requireService().add(region, parent = null)
        return region
    }

    /** Right-clicks the block, the way a client's use-on packet arrives. */
    private fun rightClicks(player: MessageCapturingPlayer, level: Level, pos: BlockPos, stack: ItemStack) {
        val hit = BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)
        player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit)
    }

    private fun assertAnchorIntact(helper: GameTestHelper, level: ServerLevel, pos: BlockPos, when_: String) {
        val state = level.getBlockState(pos)
        helper.assertValueEqual(state.block, Blocks.RESPAWN_ANCHOR, "the anchor $when_")
        helper.assertValueEqual(state.getValue(RespawnAnchorBlock.CHARGE), 4, "the anchor's charges $when_")
    }

    private fun runFor(component: Component, text: String): Component =
        component.toFlatList(component.style).first { it.string == text }

    /**
     * How many times the anchor has told this player their sneaking was
     * noticed. Counted rather than measured against the message list's length:
     * a captured player also hears the server's broadcasts, including other
     * tests' join announcements.
     */
    private fun MessageCapturingPlayer.sneakNotices(): Int =
        messages.count { it.string == "INFO Sneaking, teleportation ignored" }

    /**
     * The message reading exactly [text]. These tests span several ticks, and
     * the gametest framework announces every test's result to every player, so
     * the anchor's reply is rarely the last thing this player heard.
     */
    private fun MessageCapturingPlayer.saying(text: String): Component =
        checkNotNull(messages.firstOrNull { it.string == text }) {
            "$name heard no message saying \"$text\""
        }

    private fun cleanUp(
        helper: GameTestHelper,
        region: Region,
        plot: ChunkPos,
        player: MessageCapturingPlayer,
    ) {
        RegionsFeature.requireService().remove(region)
        EmbassyPlots.clear(embassies(helper), plot)
        EmbassyOrigins.forget(player.uuid)
        player.leave()
    }
}
