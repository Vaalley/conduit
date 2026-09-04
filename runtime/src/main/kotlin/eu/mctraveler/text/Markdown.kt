package eu.mctraveler.text

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

/**
 * The Donator perk's "markdown": vanilla's own `§` legacy formatting codes,
 * spelled with `%` instead so a Donator can type them straight into a sign or
 * `/deathmessage` without needing the actual section-sign character.
 *
 * A raw `%` followed by anything other than a valid formatting code (`§0`-`§9`,
 * `§a`-`§f`, `§k`-`§o`, `§r`) is left as a literal `%` — never an error, and
 * never eats a character a player didn't mean as a code.
 */
object Markdown {

    /**
     * Parses [text] into a styled [Component]: each `%<code>` pair becomes the
     * matching [ChatFormatting] (colors reset decorations, `%r` resets
     * everything — [Style.applyFormat]'s own semantics, identical to vanilla's
     * `§` parsing), and every other character is carried through literally.
     */
    fun parse(text: String): MutableComponent {
        val result = Component.empty()
        var style = Style.EMPTY
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                result.append(Component.literal(buffer.toString()).withStyle(style))
                buffer.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val code = if (i + 1 < text.length) ChatFormatting.getByCode(text[i + 1]) else null
            if (text[i] == '%' && code != null) {
                flush()
                style = style.applyFormat(code)
                i += 2
            } else {
                buffer.append(text[i])
                i++
            }
        }
        flush()
        return result
    }
}
