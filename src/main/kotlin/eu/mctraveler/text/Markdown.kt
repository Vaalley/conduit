package eu.mctraveler.text

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

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

    /** The first sixteen formats are the colours; the rest are the decorations and reset. */
    private const val COLOUR_COUNT = 16

    /**
     * The `%` codes that [parse] would turn into [component]: the inverse of [parse], so a
     * coloured line can be shown back the way it was typed. Only colours and the five
     * decorations are written; anything else about the style is not part of the markdown.
     */
    fun unparse(component: Component): String {
        val out = StringBuilder()
        var previous = Style.EMPTY
        component.visit(
            { style, text ->
                if (text.isNotEmpty()) {
                    out.append(codesBetween(previous, style))
                    out.append(text)
                    previous = style
                }
                java.util.Optional.empty<Unit>()
            },
            Style.EMPTY,
        )
        return out.toString()
    }

    /** What has to be typed to get from [from] to [to], resetting first when something must be switched off. */
    private fun codesBetween(from: Style, to: Style): String {
        val wanted = formatsOf(to)
        val had = formatsOf(from)
        if (wanted == had) return ""
        val codes = StringBuilder()
        // A colour resets the decorations and nothing else turns one off, so any
        // change that is not purely adding decorations starts from %r.
        if (had.isNotEmpty() && !wanted.containsAll(had)) codes.append("%r")
        val toAdd = if (had.isNotEmpty() && wanted.containsAll(had)) wanted - had.toSet() else wanted
        val ordered = toAdd.sortedBy { if (it.ordinal < COLOUR_COUNT) 0 else 1 }
        // A format's toString() is the section sign followed by its code character.
        for (format in ordered) codes.append('%').append(format.toString()[1])
        return codes.toString()
    }

    private fun formatsOf(style: Style): List<ChatFormatting> {
        val formats = mutableListOf<ChatFormatting>()
        style.color?.let { color ->
            ChatFormatting.entries.take(COLOUR_COUNT).firstOrNull { TextColor.fromLegacyFormat(it) == color }?.let(formats::add)
        }
        if (style.isBold) formats += ChatFormatting.BOLD
        if (style.isItalic) formats += ChatFormatting.ITALIC
        if (style.isUnderlined) formats += ChatFormatting.UNDERLINE
        if (style.isStrikethrough) formats += ChatFormatting.STRIKETHROUGH
        if (style.isObfuscated) formats += ChatFormatting.OBFUSCATED
        return formats
    }

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
