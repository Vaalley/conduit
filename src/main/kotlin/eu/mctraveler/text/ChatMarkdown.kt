package eu.mctraveler.text

import java.net.URI
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

/**
 * Basic Discord-style markdown for what players type in chat (issue #90):
 *
 * | You type          | You get        |
 * |-------------------|----------------|
 * | `**bold**`        | bold           |
 * | `*italic*`        | italic         |
 * | `__underline__`   | underline      |
 * | `~~strike~~`      | strikethrough  |
 *
 * They nest (`***both***`, `**bold *and italic* text**`, `__**x**__`). Two rules keep it
 * from getting in the way of ordinary typing: a delimiter that does not find its partner
 * stays as typed, and an opening delimiter must touch the word after it and a closing one
 * the word before it, so `2 * 3 * 4` is arithmetic and not italics.
 *
 * A backslash in front of a delimiter (or of another backslash) switches it off and is not
 * shown: `\*expression*` reads `*expression*`. A backslash before anything else is just a
 * backslash.
 *
 * `http://` and `https://` links are underlined and clickable, and markdown inside a link
 * is left alone (`https://example.com/a__b__c` stays one link). Markdown around a link still
 * applies to it.
 *
 * This is not the sign markdown ([Markdown], `%`-codes for Donators): anyone can use this.
 */
object ChatMarkdown {

    private const val ESCAPABLE = "*_~\\"

    private enum class Kind { BOLD, ITALIC, UNDERLINE, STRIKE }

    private sealed interface Token

    private class Text(val text: String) : Token

    private class Link(val display: String, val uri: URI) : Token

    /** A run of `*`, `_` or `~` that may open and/or close styled spans. */
    private class Delim(val char: Char, var count: Int, var canOpen: Boolean, var canClose: Boolean) : Token {
        val opens = mutableListOf<Kind>()
        val closes = mutableListOf<Kind>()
    }

    /**
     * [raw] as a styled component, or null when it holds nothing to render (no markdown, no
     * link, no escape) — so plain chat is sent exactly as the player typed and signed it.
     */
    fun format(raw: String): Component? {
        var changed = false
        val component = render(raw) { changed = true }
        return if (changed) component else null
    }

    /** [raw] as a styled component, always. */
    fun parse(raw: String): MutableComponent = render(raw) {}

    // -- tokens --

    private fun tokenize(text: String, onEscape: () -> Unit): MutableList<Token> {
        val tokens = mutableListOf<Token>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                tokens += Text(buffer.toString())
                buffer.setLength(0)
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
                buffer.append(text[i + 1])
                onEscape()
                i += 2
                continue
            }
            val link = linkAt(text, i)
            if (link != null) {
                flush()
                tokens += Link(link.first, link.second)
                i += link.first.length
                continue
            }
            if (c == '*' || c == '_' || c == '~') {
                var end = i
                while (end < text.length && text[end] == c) end++
                val count = end - i
                if (c != '*' && count < 2) {
                    buffer.append(c)
                    i = end
                    continue
                }
                flush()
                val before = if (i > 0) text[i - 1] else ' '
                val after = if (end < text.length) text[end] else ' '
                var canOpen = !after.isWhitespace()
                var canClose = !before.isWhitespace()
                if (c == '_') {
                    // snake__case__name is one word, not an underline
                    canOpen = canOpen && !before.isLetterOrDigit()
                    canClose = canClose && !after.isLetterOrDigit()
                }
                tokens += Delim(c, count, canOpen, canClose)
                i = end
                continue
            }
            buffer.append(c)
            i++
        }
        flush()
        return tokens
    }

    // -- pairing --

    /**
     * Pairs closers with the nearest opener before them, the way CommonMark does emphasis:
     * `*` prefers a strong (two-character) pair when both sides have two to give, `__` and
     * `~~` always use exactly two. Delimiters caught between a pair are left as typed.
     */
    private fun pair(tokens: List<Token>) {
        for (closerIndex in tokens.indices) {
            val closer = tokens[closerIndex] as? Delim ?: continue
            if (!closer.canClose) continue
            while (closer.count > 0) {
                var openerIndex = closerIndex - 1
                var opener: Delim? = null
                while (openerIndex >= 0) {
                    val candidate = tokens[openerIndex]
                    if (candidate is Delim && candidate.char == closer.char && candidate.canOpen &&
                        candidate.count > 0 && (closer.char == '*' || (candidate.count >= 2 && closer.count >= 2))
                    ) {
                        opener = candidate
                        break
                    }
                    openerIndex--
                }
                if (opener == null) break
                val use = if (closer.char == '*' && (opener.count < 2 || closer.count < 2)) 1 else 2
                val kind = when (closer.char) {
                    '*' -> if (use == 2) Kind.BOLD else Kind.ITALIC
                    '_' -> Kind.UNDERLINE
                    else -> Kind.STRIKE
                }
                opener.count -= use
                closer.count -= use
                opener.opens += kind
                closer.closes += kind
                for (between in openerIndex + 1 until closerIndex) {
                    (tokens[between] as? Delim)?.let {
                        it.canOpen = false
                        it.canClose = false
                    }
                }
            }
        }
    }

    // -- rendering --

    private fun render(raw: String, onChange: () -> Unit): MutableComponent {
        val tokens = tokenize(raw, onChange)
        pair(tokens)

        val result = Component.empty()
        val active = IntArray(Kind.entries.size)
        val buffer = StringBuilder()

        fun style(): Style {
            var style = Style.EMPTY
            if (active[Kind.BOLD.ordinal] > 0) style = style.withBold(true)
            if (active[Kind.ITALIC.ordinal] > 0) style = style.withItalic(true)
            if (active[Kind.UNDERLINE.ordinal] > 0) style = style.withUnderlined(true)
            if (active[Kind.STRIKE.ordinal] > 0) style = style.withStrikethrough(true)
            return style
        }

        fun flush() {
            if (buffer.isNotEmpty()) {
                result.append(Component.literal(buffer.toString()).withStyle(style()))
                buffer.setLength(0)
            }
        }

        for (token in tokens) {
            when (token) {
                is Text -> buffer.append(token.text)
                is Link -> {
                    flush()
                    onChange()
                    val linkStyle = style().withUnderlined(true)
                        .withClickEvent(ClickEvent.OpenUrl(token.uri))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal(token.display)))
                    result.append(Component.literal(token.display).withStyle(linkStyle))
                }
                is Delim -> {
                    if (token.closes.isNotEmpty()) {
                        flush()
                        onChange()
                        token.closes.forEach { active[it.ordinal]-- }
                    }
                    buffer.append(token.char.toString().repeat(token.count))
                    if (token.opens.isNotEmpty()) {
                        flush()
                        onChange()
                        token.opens.forEach { active[it.ordinal]++ }
                    }
                }
            }
        }
        flush()
        return result
    }

    // -- links --

    /** The link starting at [start] — its text (trailing punctuation trimmed) and address — or null. */
    private fun linkAt(text: String, start: Int): Pair<String, URI>? {
        val scheme = when {
            text.startsWith("https://", start, ignoreCase = true) -> "https"
            text.startsWith("http://", start, ignoreCase = true) -> "http"
            else -> return null
        }
        if (start > 0 && text[start - 1].isLetterOrDigit()) return null
        var end = start
        while (end < text.length && !text[end].isWhitespace() && text[end] != '<' && text[end] != '>') end++
        val display = trimTrailing(text.substring(start, end))
        if (display.length <= scheme.length + 3) return null
        // The scheme is lower-cased for the click event, which only accepts http/https as such.
        val uri = runCatching { URI(scheme + display.substring(scheme.length)) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != scheme || uri.host.isNullOrBlank()) return null
        return display to uri
    }

    /** Drops what sentence punctuation and markdown closers tack onto the end of a link. */
    private fun trimTrailing(url: String): String {
        var end = url.length
        while (end > 0) {
            val last = url[end - 1]
            if (last in ".,!?;:'\"*_~") {
                end--
                continue
            }
            val opener = when (last) {
                ')' -> '('
                ']' -> '['
                '}' -> '{'
                else -> break
            }
            val body = url.substring(0, end)
            if (body.count { it == last } > body.count { it == opener }) end-- else break
        }
        return url.substring(0, end)
    }
}
