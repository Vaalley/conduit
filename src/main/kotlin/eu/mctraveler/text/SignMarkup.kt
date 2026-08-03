package eu.mctraveler.text

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import kotlin.math.floor
import kotlin.math.roundToInt

private data class SignMarkupSpan(
    val text: String,
    val style: Style,
    val effect: SignMarkupEffect?,
)

private sealed interface SignMarkupEffect {
    data class Gradient(val stops: List<Int>) : SignMarkupEffect

    data class Rainbow(val degrees: Double) : SignMarkupEffect
}

private data class OpenSignMarkupTag(
    val name: String,
    val previousStyle: Style,
    val effect: SignMarkupEffect?,
)

private data class ParsedSignMarkup(
    val spans: List<SignMarkupSpan>,
    val plain: String,
    val problems: List<SignMarkupProblem>,
    val hadMarkup: Boolean,
)

/** Limits applied while expanding a single sign line. */
data class SignMarkupLimits(
    val maxComponentsPerLine: Int,
) {
    init {
        require(maxComponentsPerLine > 0) { "maxComponentsPerLine must be positive" }
    }

    companion object {
        val DEFAULT = SignMarkupLimits(maxComponentsPerLine = 96)
    }
}

/** A problem found in source markup; parsing continues after each problem. */
data class SignMarkupProblem(
    val message: String,
    val position: Int,
)

/** The rendered line and diagnostics produced from one source string. */
data class SignMarkupResult(
    val component: Component,
    val problems: List<SignMarkupProblem>,
    val hadMarkup: Boolean,
)

/**
 * Pure parser for player-authored sign formatting.
 *
 * Gradients use straight-line interpolation in sRGB, and rainbow colors use a
 * full-saturation, full-value HSV cycle.
 */
object SignMarkup {
    /** Parses [source], retaining malformed markup literally and reporting problems. */
    fun render(
        source: String,
        limits: SignMarkupLimits = SignMarkupLimits.DEFAULT,
    ): SignMarkupResult {
        val parsed = parse(source)
        val expanded = parsed.spans.flatMap(::expand)
        if (expanded.size > limits.maxComponentsPerLine) {
            return SignMarkupResult(
                component = Component.literal(parsed.plain),
                problems = parsed.problems + SignMarkupProblem(
                    message = "component cap exceeded",
                    position = source.length,
                ),
                hadMarkup = parsed.hadMarkup,
            )
        }

        return SignMarkupResult(
            component = compose(expanded),
            problems = parsed.problems,
            hadMarkup = parsed.hadMarkup,
        )
    }

    /** Removes markup while preserving the readable text. */
    fun strip(source: String): String = parse(source).plain

    private fun parse(source: String): ParsedSignMarkup {
        val spans = mutableListOf<SignMarkupSpan>()
        val problems = mutableListOf<SignMarkupProblem>()
        val plain = StringBuilder()
        val text = StringBuilder()
        val tags = ArrayDeque<OpenSignMarkupTag>()
        var style = Style.EMPTY
        var hadMarkup = false

        fun activeEffect(): SignMarkupEffect? =
            tags.lastOrNull { it.effect != null }?.effect

        fun flush() {
            if (text.isEmpty()) return
            val value = text.toString()
            spans += SignMarkupSpan(value, style, activeEffect())
            plain.append(value)
            text.clear()
        }

        fun literal(value: String) {
            text.append(value)
        }

        fun problem(message: String, position: Int) {
            problems += SignMarkupProblem(message, position)
        }

        var index = 0
        while (index < source.length) {
            when (val character = source[index]) {
                '\\' -> {
                    if (index + 1 < source.length && source[index + 1] in charArrayOf('<', '&', '\\')) {
                        hadMarkup = true
                        literal(source[index + 1].toString())
                        index += 2
                    } else {
                        literal(character.toString())
                        index++
                    }
                }

                '§' -> {
                    hadMarkup = true
                    index++
                }

                '&' -> {
                    val legacy = source.legacyCodeAt(index)
                    if (legacy != null) {
                        hadMarkup = true
                        flush()
                        style = legacy.apply(style)
                        index += legacy.consumed
                    } else {
                        literal("&")
                        index++
                    }
                }

                '<' -> {
                    val end = source.indexOf('>', index + 1)
                    if (end < 0) {
                        hadMarkup = true
                        literal(source.substring(index))
                        problem("unclosed tag", index)
                        index = source.length
                        continue
                    }

                    val rawTag = source.substring(index, end + 1)
                    val contents = source.substring(index + 1, end)
                    hadMarkup = true
                    if (contents.startsWith('/')) {
                        flush()
                        val name = contents.substring(1).lowercase()
                        if (tags.isEmpty()) {
                            problem("closing tag with nothing open", index)
                        } else if (name.isNotEmpty() && name !in tags.map { it.name }) {
                            literal(rawTag)
                            problem("unknown closing tag", index)
                        } else if (name.isEmpty()) {
                            val tag = tags.removeLast()
                            style = tag.previousStyle
                        } else if (tags.last().name == name) {
                            val tag = tags.removeLast()
                            style = tag.previousStyle
                        } else {
                            problem("closing tag does not match the open tag", index)
                        }
                    } else {
                        val tag = parseTag(contents)
                        if (tag == null) {
                            literal(rawTag)
                            problem("unknown or malformed tag", index)
                        } else if (tag.pointMarker) {
                            flush()
                            tags.clear()
                            style = Style.EMPTY
                        } else {
                            flush()
                            tags.addLast(OpenSignMarkupTag(tag.name, style, tag.effect))
                            style = tag.style(style)
                        }
                    }
                    index = end + 1
                }

                else -> {
                    literal(character.toString())
                    index++
                }
            }
        }
        flush()

        return ParsedSignMarkup(spans, plain.toString(), problems, hadMarkup)
    }

    private fun parseTag(contents: String): ParsedTag? {
        val parts = contents.split(':')
        val name = parts.firstOrNull()?.lowercase() ?: return null
        if (name.isEmpty()) return null

        val color = namedColors[name]
        if (color != null && parts.size == 1) {
            return ParsedTag(name, style = { it.withColor(color) })
        }
        if (name == "grey" && parts.size == 1) {
            return ParsedTag(name, style = { it.withColor(ChatFormatting.GRAY) })
        }
        if (name.startsWith('#') && parts.size == 1) {
            return parseHex(name)?.let { value ->
                ParsedTag(name, style = { it.withColor(value) })
            }
        }

        return when (name) {
            "b", "bold" -> simpleDecoration(name) { it.withBold(true) }
            "i", "italic" -> simpleDecoration(name) { it.withItalic(true) }
            "u", "underline" -> simpleDecoration(name) { it.withUnderlined(true) }
            "st", "strikethrough" -> simpleDecoration(name) { it.withStrikethrough(true) }
            "obf", "obfuscated" -> simpleDecoration(name) { it.withObfuscated(true) }
            "sga" -> ParsedTag(
                name,
                style = {
                    it.withFont(FontDescription.Resource(Identifier.parse("minecraft:alt")))
                },
            )
            "illager" -> ParsedTag(
                name,
                style = {
                    it.withFont(FontDescription.Resource(Identifier.parse("minecraft:illageralt")))
                },
            )
            "enchant" -> ParsedTag(
                name,
                style = {
                    it.withFont(FontDescription.Resource(Identifier.parse("minecraft:alt")))
                        .withObfuscated(true)
                },
            )
            "reset" -> ParsedTag(name, pointMarker = true)
            "gradient" -> {
                if (parts.size < 3) {
                    null
                } else {
                    val stops = parts.drop(1).map { parseHex(it) }
                    if (stops.any { it == null }) {
                        null
                    } else {
                        ParsedTag(
                            name = name,
                            effect = SignMarkupEffect.Gradient(stops.filterNotNull()),
                        )
                    }
                }
            }
            "rainbow" -> {
                if (parts.size > 2) {
                    null
                } else {
                    val degrees = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    if (parts.size == 2 && parts[1].toDoubleOrNull() == null) {
                        null
                    } else {
                        ParsedTag(name, effect = SignMarkupEffect.Rainbow(degrees))
                    }
                }
            }
            else -> null
        }
    }

    private fun simpleDecoration(
        name: String,
        style: (Style) -> Style,
    ): ParsedTag = ParsedTag(name, style = style)

    private fun expand(span: SignMarkupSpan): List<SignMarkupSpan> {
        val effect = span.effect ?: return listOf(span)
        val characters = span.text.codePoints()
            .toArray()
            .map { String(Character.toChars(it)) }
        if (characters.isEmpty()) return emptyList()

        return characters.mapIndexed { index, character ->
            val color = when (effect) {
                is SignMarkupEffect.Gradient -> gradientColor(effect.stops, index, characters.size)
                is SignMarkupEffect.Rainbow -> hsvColor(
                    hue = (effect.degrees + index * 360.0 / characters.size) / 360.0,
                )
            }
            SignMarkupSpan(
                text = character,
                style = span.style.withColor(color),
                effect = null,
            )
        }
    }

    private fun compose(spans: List<SignMarkupSpan>): Component {
        if (spans.isEmpty()) return Component.empty()
        if (spans.size == 1) {
            return Component.literal(spans[0].text).withStyle(spans[0].style)
        }
        return spans.fold(Component.empty()) { component, span ->
            component.append(Component.literal(span.text).withStyle(span.style))
        }
    }

    private fun gradientColor(stops: List<Int>, index: Int, size: Int): Int {
        if (stops.size == 1 || size == 1) return stops.first()
        val progress = index.toDouble() / (size - 1)
        val scaled = progress * (stops.size - 1)
        val segment = floor(scaled).toInt().coerceAtMost(stops.size - 2)
        val local = scaled - segment
        return lerpColor(stops[segment], stops[segment + 1], local)
    }

    private fun lerpColor(first: Int, second: Int, amount: Double): Int {
        fun channel(color: Int, shift: Int): Int = color shr shift and 0xff
        fun lerp(shift: Int): Int =
            (channel(first, shift) + (channel(second, shift) - channel(first, shift)) * amount)
                .roundToInt()

        return lerp(16) shl 16 or (lerp(8) shl 8) or lerp(0)
    }

    private fun hsvColor(hue: Double): Int {
        val normalized = ((hue % 1.0) + 1.0) % 1.0
        val scaled = normalized * 6.0
        val sector = floor(scaled).toInt()
        val fraction = scaled - sector
        val value = when (sector % 6) {
            0 -> Triple(1.0, fraction, 0.0)
            1 -> Triple(1.0 - fraction, 1.0, 0.0)
            2 -> Triple(0.0, 1.0, fraction)
            3 -> Triple(0.0, 1.0 - fraction, 1.0)
            4 -> Triple(fraction, 0.0, 1.0)
            else -> Triple(1.0, 0.0, 1.0 - fraction)
        }
        return (value.first * 255).roundToInt() shl 16 or
            ((value.second * 255).roundToInt() shl 8) or
            (value.third * 255).roundToInt()
    }

    private data class ParsedTag(
        val name: String,
        val style: (Style) -> Style = { it },
        val effect: SignMarkupEffect? = null,
        val pointMarker: Boolean = false,
    )

    private data class LegacyCode(
        val consumed: Int,
        val apply: (Style) -> Style,
    )

    private fun String.legacyCodeAt(index: Int): LegacyCode? {
        if (index + 1 >= length) return null
        if (this[index + 1] == '#') {
            if (index + 7 >= length) return null
            val value = parseHex(substring(index + 1, index + 8)) ?: return null
            return LegacyCode(8) { style ->
                style.withColor(value)
                    .withBold(false)
                    .withItalic(false)
                    .withUnderlined(false)
                    .withStrikethrough(false)
                    .withObfuscated(false)
            }
        }
        val formatting = legacyFormats[this[index + 1].lowercaseChar()] ?: return null
        return LegacyCode(2) { style ->
            when {
                formatting == ChatFormatting.RESET -> Style.EMPTY
                formatting in legacyColors -> style.withColor(formatting)
                    .withBold(false)
                    .withItalic(false)
                    .withUnderlined(false)
                    .withStrikethrough(false)
                    .withObfuscated(false)
                else -> style.applyLegacyFormat(formatting)
            }
        }
    }

    private val namedColors = mapOf(
            "black" to ChatFormatting.BLACK,
            "dark_blue" to ChatFormatting.DARK_BLUE,
            "dark_green" to ChatFormatting.DARK_GREEN,
            "dark_aqua" to ChatFormatting.DARK_AQUA,
            "dark_red" to ChatFormatting.DARK_RED,
            "dark_purple" to ChatFormatting.DARK_PURPLE,
            "gold" to ChatFormatting.GOLD,
            "gray" to ChatFormatting.GRAY,
            "dark_gray" to ChatFormatting.DARK_GRAY,
            "blue" to ChatFormatting.BLUE,
            "green" to ChatFormatting.GREEN,
            "aqua" to ChatFormatting.AQUA,
            "red" to ChatFormatting.RED,
            "light_purple" to ChatFormatting.LIGHT_PURPLE,
            "yellow" to ChatFormatting.YELLOW,
            "white" to ChatFormatting.WHITE,
        )

    private val legacyFormats = mapOf(
            '0' to ChatFormatting.BLACK,
            '1' to ChatFormatting.DARK_BLUE,
            '2' to ChatFormatting.DARK_GREEN,
            '3' to ChatFormatting.DARK_AQUA,
            '4' to ChatFormatting.DARK_RED,
            '5' to ChatFormatting.DARK_PURPLE,
            '6' to ChatFormatting.GOLD,
            '7' to ChatFormatting.GRAY,
            '8' to ChatFormatting.DARK_GRAY,
            '9' to ChatFormatting.BLUE,
            'a' to ChatFormatting.GREEN,
            'b' to ChatFormatting.AQUA,
            'c' to ChatFormatting.RED,
            'd' to ChatFormatting.LIGHT_PURPLE,
            'e' to ChatFormatting.YELLOW,
            'f' to ChatFormatting.WHITE,
            'k' to ChatFormatting.OBFUSCATED,
            'l' to ChatFormatting.BOLD,
            'm' to ChatFormatting.STRIKETHROUGH,
            'n' to ChatFormatting.UNDERLINE,
            'o' to ChatFormatting.ITALIC,
            'r' to ChatFormatting.RESET,
        )

    private val legacyColors = legacyFormats
            .filterKeys { it in '0'..'9' || it in 'a'..'f' }
            .values
            .toSet()

    private fun parseHex(value: String): Int? =
        if (value.matches(Regex("#[0-9a-fA-F]{6}"))) {
            value.substring(1).toInt(16)
        } else {
            null
        }
}
