package eu.mctraveler.gametest

import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import kotlin.math.floor
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component

/**
 * Shared setup for the region gametests: regions are built through the real
 * commands, so every test starts from state a player could actually create.
 *
 * Keep the coordinates small. The gametest batch lays structures out only
 * about 15 blocks apart, so regions and walks that stray further can collide
 * with a neighbouring test's.
 */
fun createRegion(
    helper: GameTestHelper,
    player: MessageCapturingPlayer,
    from: Pair<Double, Double>,
    to: Pair<Double, Double>,
): Region {
    player.standAt(helper, from.first, 1.0, from.second)
    player.runCommand("rg start")
    player.standAt(helper, to.first, 1.0, to.second)
    player.runCommand("rg end")
    val service = RegionsFeature.requireService()
    return checkNotNull(
        service.regionAt("world", floor(player.x).toInt(), 1, floor(player.z).toInt()),
    ) { "region creation for ${player.gameProfile.name} did not take" }
}

/** The Portal's one refusal, naming the region that turned the player away. */
fun protectedBy(title: String): Component =
    Paint.error("This area is protected by ", Paint.red(title))

fun MessageCapturingPlayer.wasRefusedBy(title: String): Boolean =
    messages.any { it == protectedBy(title) }

/**
 * The tab-completions this player's client would be offered for [command]
 * (no leading slash), with the cursor at the end of what they have typed.
 */
fun MessageCapturingPlayer.suggestionsFor(command: String): List<String> {
    val dispatcher = level().server.commands.dispatcher
    val parsed = dispatcher.parse(command, createCommandSourceStack())
    return dispatcher.getCompletionSuggestions(parsed).join().list.map { it.text }
}
