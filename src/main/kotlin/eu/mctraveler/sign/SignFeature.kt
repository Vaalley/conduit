package eu.mctraveler.sign

import eu.mctraveler.text.Paint
import eu.mctraveler.text.SignMarkup
import net.minecraft.server.network.FilteredText
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.entity.SignText

/**
 * Applies player-submitted sign lines after vanilla has performed its edit checks.
 *
 * The mixin is the registration seam for this ticket; there is no event or command
 * to register here, so this object deliberately has no empty register method.
 */
object SignFeature {
    @JvmStatic
    fun renderSubmittedLines(
        player: Player,
        lines: List<FilteredText>,
        text: SignText,
    ): SignText {
        var rendered = text
        val problems = mutableListOf<String>()

        lines.take(4).forEachIndexed { index, line ->
            val raw = SignMarkup.render(line.raw())
            val filtered = SignMarkup.render(line.filteredOrEmpty())
            rendered = rendered.setMessage(index, raw.component, filtered.component)
            problems += raw.problems
                .distinct()
                .map { "line ${index + 1}: ${it.message}" }
            problems += filtered.problems
                .distinct()
                .filterNot { it in raw.problems }
                .map { "line ${index + 1} filtered: ${it.message}" }
        }

        if (problems.isNotEmpty()) {
            player.sendSystemMessage(
                Paint.warning("Sign markup: ", problems.joinToString("; ")),
            )
        }
        return rendered
    }
}
