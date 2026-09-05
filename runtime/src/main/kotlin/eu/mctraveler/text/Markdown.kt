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
     * The per-viewer name token: recognised as a literal 6-char run (case
     * sensitive), turned into a sentinel run by [parse], and substituted for the
     * reading player's name on the way out (see [SignNames]).
     */
    private const val NAME_TOKEN = "<name>"

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
            if (text.startsWith(NAME_TOKEN, i)) {
                flush()
                result.append(
                    Component.literal(NAME_TOKEN)
                        .withStyle(style.withInsertion(SignNames.SIGN_NAME_SENTINEL)),
                )
                i += NAME_TOKEN.length
                continue
            }
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
