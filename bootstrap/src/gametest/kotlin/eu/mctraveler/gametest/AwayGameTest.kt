package eu.mctraveler.gametest

import eu.mctraveler.away.AwayFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.chat.TextColor
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * The away system at the running-server seam (spec stories 16–18; Portal:
 * features/AwayFeature.ts). Broadcast wording, styling, and timing are asserted
 * through what players actually receive; all timing is in server ticks.
 */
class AwayGameTest {

    // --- Story 16: /away marks the sender away immediately ---

    @GameTest(maxTicks = 100)
    fun awayCommandMarksAwayAndBroadcastsToEveryone(helper: GameTestHelper) {
        val actor = AwayTestPlayer.join(helper, "AwayManual")
        val observer = AwayTestPlayer.join(helper, "AwayWitness")
        helper.runAfterDelay(2) { actor.runCommand("away") }
        helper.runAfterDelay(5) {
            assertTransitionBroadcast(helper, actor, "AwayManual", " is now away")
            assertTransitionBroadcast(helper, observer, "AwayManual", " is now away")
            actor.leave()
            observer.leave()
            helper.succeed()
        }
    }

    // --- Story 17: auto-away after five idle minutes, measured in ticks ---

    @GameTest(maxTicks = 400)
    fun autoAwayAfterFiveIdleMinutesInTicks(helper: GameTestHelper) {
        val actor = AwayTestPlayer.join(helper, "AwayIdle")
        helper.runAfterDelay(2) { AwayFeature.fastForward(actor, 5700) }
        // A full checker cycle later the player is still just short of the timeout.
        helper.runAfterDelay(2 + AwayFeature.CHECK_INTERVAL_TICKS + 15) {
            helper.assertTrue(
                actor.received.none { it.string == "AwayIdle is now away" },
                "auto-away fired before five idle minutes",
            )
            AwayFeature.fastForward(actor, 300) // now past the 6000-tick timeout
        }
        helper.succeedWhen {
            assertTransitionBroadcast(helper, actor, "AwayIdle", " is now away")
            actor.leave()
        }
    }

    @GameTest(maxTicks = 400)
    fun interactionResetsTheIdleClock(helper: GameTestHelper) {
        val name = "AwayReset"
        val actor = AwayTestPlayer.join(helper, name)
        helper.runAfterDelay(2) { AwayFeature.fastForward(actor, 5000) }
        helper.runAfterDelay(4) { chat(helper, actor) } // interaction: the idle clock restarts
        helper.runAfterDelay(6) { AwayFeature.fastForward(actor, 5000) }
        // Total aging is 10000 ticks, but only ~5000 since the chat line: not away.
        helper.runAfterDelay(6 + AwayFeature.CHECK_INTERVAL_TICKS + 15) {
            helper.assertTrue(
                actor.received.none { it.string == "$name is now away" },
                "an interaction did not reset the idle clock",
            )
            AwayFeature.fastForward(actor, 1100) // now past the timeout since the chat
        }
        helper.succeedWhen {
            helper.assertTrue(
                actor.received.any { it.string == "$name is now away" },
                "no auto-away broadcast after the reset idle clock ran out",
            )
            actor.leave()
        }
    }

    // --- Story 18: the 3-second /away cooldown after returning from away ---

    @GameTest(maxTicks = 200)
    fun awayCooldownAfterReturningRepliesWithExactError(helper: GameTestHelper) {
        val name = "AwayCool"
        val actor = AwayTestPlayer.join(helper, name)
        helper.runAfterDelay(2) { actor.runCommand("away") }
        helper.runAfterDelay(5) {
            // Return via chat; the 3 s (60 tick) cooldown starts this tick.
            chat(helper, actor)
            // Retrying within the same tick leaves the full 3.0 s remaining.
            actor.runCommand("away")
            assertCooldownError(helper, actor, "3")
            helper.assertTrue(
                actor.received.count { it.string == "$name is now away" } == 1,
                "/away must not re-mark $name away during the cooldown",
            )
        }
        helper.runAfterDelay(7) {
            // Two ticks later 58 ticks remain: 2.9 s at the Portal's 0.1 s precision.
            actor.runCommand("away")
            assertCooldownError(helper, actor, "2.9")
        }
        helper.runAfterDelay(9) {
            AwayFeature.fastForward(actor, AwayFeature.RETURN_COOLDOWN_TICKS)
            actor.runCommand("away")
            helper.assertTrue(
                actor.received.count { it.string == "$name is now away" } == 2,
                "/away should work again once the cooldown has expired",
            )
            actor.leave()
            helper.succeed()
        }
    }

    /** Asserts the Portal's exact cooldown error, styling included. */
    private fun assertCooldownError(helper: GameTestHelper, actor: AwayTestPlayer, seconds: String) {
        val expected = "ERROR You cannot use /away again for another $seconds seconds yet"
        val line = actor.received.firstOrNull { it.string == expected }
            ?: return helper.fail(
                "${actor.gameProfile.name} never received \"$expected\"; " +
                    "system messages seen: ${actor.received.map { it.string }}",
            )
        val red = TextColor.fromLegacyFormat(ChatFormatting.RED)
        val gray = TextColor.fromLegacyFormat(ChatFormatting.GRAY)
        val rendered = line.toFlatList(line.style).map { Triple(it.string, it.style.color, it.style.isBold) }
        val expectedParts = listOf(
            Triple("ERROR", red, true),
            Triple(" ", null, false),
            Triple("You cannot use /away again for another ", gray, false),
            Triple(seconds, red, false),
            Triple(" seconds yet", gray, false),
        )
        helper.assertTrue(
            rendered == expectedParts,
            "cooldown error styling mismatch: $rendered",
        )
    }

    // --- Story 17: leaving cleans up away state (nothing survives to the next session) ---

    @GameTest(maxTicks = 200)
    fun leavingCleansUpAwayStateAndCooldown(helper: GameTestHelper) {
        val name = "AwayLeave"
        val first = AwayTestPlayer.join(helper, name)
        helper.runAfterDelay(2) { first.runCommand("away") }
        helper.runAfterDelay(5) {
            chat(helper, first) // return from away: the cooldown starts...
            first.leave() // ...but leaving discards all away state
        }
        lateinit var rejoined: AwayTestPlayer
        helper.runAfterDelay(8) { rejoined = AwayTestPlayer.join(helper, name) }
        helper.runAfterDelay(11) {
            // Still inside what would be the old cooldown window; a fresh session has none.
            rejoined.runCommand("away")
            helper.assertTrue(
                rejoined.received.none { it.string.startsWith("ERROR You cannot use /away") },
                "the /away cooldown survived a leave and rejoin",
            )
            assertTransitionBroadcast(helper, rejoined, name, " is now away")
            rejoined.leave()
            helper.succeed()
        }
    }

    // --- Story 17: any interaction while away clears it, with the matching broadcast ---

    @GameTest(maxTicks = 100)
    fun chatClearsAway(helper: GameTestHelper) =
        interactionClearsAway(helper, "AwayChat") { actor -> chat(helper, actor) }

    /** Sends a chat line from [actor] through the server's real chat broadcast path. */
    private fun chat(helper: GameTestHelper, actor: AwayTestPlayer) {
        helper.level.server.playerList.broadcastChatMessage(
            PlayerChatMessage.unsigned(actor.uuid, "back at the keyboard"),
            actor,
            ChatType.bind(ChatType.CHAT, actor),
        )
    }

    @GameTest(maxTicks = 100)
    fun commandClearsAway(helper: GameTestHelper) =
        interactionClearsAway(helper, "AwayCommand") { actor -> actor.runCommand("list") }

    @GameTest(maxTicks = 100)
    fun blockBreakClearsAway(helper: GameTestHelper) {
        helper.setBlock(BlockPos(2, 2, 2), Blocks.STONE)
        interactionClearsAway(helper, "AwayBreak") { actor ->
            helper.assertTrue(
                actor.gameMode.destroyBlock(helper.absolutePos(BlockPos(2, 2, 2))),
                "precondition: could not break the stone block",
            )
        }
    }

    @GameTest(maxTicks = 100)
    fun blockPlaceClearsAway(helper: GameTestHelper) {
        helper.setBlock(BlockPos(2, 2, 2), Blocks.STONE)
        interactionClearsAway(helper, "AwayPlace") { actor ->
            val target = helper.absolutePos(BlockPos(2, 2, 2))
            val hit = BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false)
            actor.gameMode.useItemOn(actor, actor.level(), ItemStack(Items.STONE), InteractionHand.MAIN_HAND, hit)
        }
    }

    @GameTest(maxTicks = 100)
    fun itemUseClearsAway(helper: GameTestHelper) =
        interactionClearsAway(helper, "AwayUse") { actor ->
            actor.gameMode.useItem(actor, actor.level(), ItemStack(Items.STICK), InteractionHand.MAIN_HAND)
        }

    @GameTest(maxTicks = 100)
    fun movementClearsAway(helper: GameTestHelper) =
        interactionClearsAway(helper, "AwayMove") { actor ->
            actor.teleportTo(actor.x + 1.0, actor.y, actor.z)
        }

    /**
     * Shared skeleton: mark the actor away with /away, perform one interaction,
     * and expect the exact "no longer away" broadcast.
     */
    private fun interactionClearsAway(
        helper: GameTestHelper,
        name: String,
        interact: (AwayTestPlayer) -> Unit,
    ) {
        val actor = AwayTestPlayer.join(helper, name)
        helper.runAfterDelay(2) { actor.runCommand("away") }
        helper.runAfterDelay(5) {
            helper.assertTrue(
                actor.received.any { it.string == "$name is now away" },
                "precondition: /away did not mark $name away",
            )
            interact(actor)
        }
        helper.runAfterDelay(10) {
            assertTransitionBroadcast(helper, actor, name, " is no longer away")
            actor.leave()
            helper.succeed()
        }
    }

    // --- Shared assertion: the Portal's transition broadcast, exact text and styling ---

    /** Asserts [receiver] saw the gray `<green subject><suffix>` broadcast line. */
    private fun assertTransitionBroadcast(
        helper: GameTestHelper,
        receiver: AwayTestPlayer,
        subject: String,
        suffix: String,
    ) {
        val expected = subject + suffix
        val line = receiver.received.firstOrNull { it.string == expected }
            ?: return helper.fail(
                "${receiver.gameProfile.name} never received \"$expected\"; " +
                    "system messages seen: ${receiver.received.map { it.string }}",
            )
        // Resolve style inheritance the way the client renders it.
        val rendered = line.toFlatList(line.style).map { it.string to it.style.color }
        val expectedParts = listOf(
            subject to TextColor.fromLegacyFormat(ChatFormatting.GREEN),
            suffix to TextColor.fromLegacyFormat(ChatFormatting.GRAY),
        )
        helper.assertTrue(
            rendered == expectedParts,
            "broadcast \"$expected\" is not a gray line with a green username: $rendered",
        )
    }
}
