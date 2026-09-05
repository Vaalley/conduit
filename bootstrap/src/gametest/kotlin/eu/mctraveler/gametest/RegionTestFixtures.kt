package eu.mctraveler.gametest

import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionTracker
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import kotlin.math.floor
import net.minecraft.core.Direction
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

/**
 * Turns [id] on or off directly on the region the player stands in, and
 * redraws the sidebar live — the gametest equivalent of a GUI toggle now that
 * `/rg flags` is a menu rather than a chat command. Mirrors what
 * [eu.mctraveler.region.RegionFlagsMenu]'s own toggle handler does: mutate,
 * save, redraw.
 */
fun MessageCapturingPlayer.setFlag(id: String, on: Boolean) {
    val region = checkNotNull(RegionTracker.regionOf(this)) { "not standing in a region" }
    if (on) region.flags.add(id) else region.flags.remove(id)
    RegionsFeature.requireService().save()
    RegionTracker.redraw(level().server, region)
}

/** Points the player at a cardinal direction, so `/rg extend` reads their facing. */
fun MessageCapturingPlayer.face(direction: Direction) {
    val yaw = direction.toYRot()
    setYRot(yaw)
    yHeadRot = yaw
    check(this.direction == direction) { "facing did not take: ${this.direction} != $direction" }
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
