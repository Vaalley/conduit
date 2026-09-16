package eu.mctraveler.cosmetic

import eu.mctraveler.text.Paint
import java.text.BreakIterator
import kotlin.math.roundToInt
import net.minecraft.network.chat.Component

object NameStyle {
    data class Style(
        val tag: String?,
        val color: Int?,
        val gradient: Pair<Int, Int>?,
        val donator: Boolean,
        val rankColor: Paint,
    )

    fun render(name: String, style: Style): Component {
        if (style.tag == null && !style.donator && style.gradient == null) {
            return if (style.color != null) Paint.rgb(style.color)(name) else style.rankColor(name)
        }
        val rendered = Component.empty()
        style.tag?.let { rendered.append(Paint.white(it, " ")) }
        if (style.donator) rendered.append(Paint.gold("✦ "))
        when {
            style.gradient != null && name.isNotEmpty() -> {
                val (from, to) = style.gradient
                name.forEachIndexed { index, character ->
                    val fraction = if (name.length == 1) 0.0 else index.toDouble() / (name.length - 1)
                    rendered.append(Paint.rgb(interpolate(from, to, fraction))(character.toString()))
                }
            }
            style.color != null -> rendered.append(Paint.rgb(style.color)(name))
            else -> rendered.append(style.rankColor(name))
        }
        return rendered
    }

    fun parseHex(input: String): Int? {
        val value = input.removePrefix("#")
        if (value.length != 6 || value.any { it !in "0123456789abcdefABCDEF" }) return null
        return value.toIntOrNull(16)
    }

    fun validateTag(input: String): String? {
        if (input.isEmpty() || input.contains('§') || input.any(Char::isWhitespace)) return null
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(input)
        var count = 0
        var start = iterator.first()
        while (start != BreakIterator.DONE) {
            val end = iterator.next()
            if (end == BreakIterator.DONE) break
            count++
            start = end
        }
        if (count != 1) return null
        if (input.codePoints().count() == 1L) {
            val type = Character.getType(input.codePointAt(0))
            if (type in FORBIDDEN_STANDALONE_TYPES) return null
        }
        return input
    }

    private fun interpolate(from: Int, to: Int, fraction: Double): Int {
        fun channel(color: Int, shift: Int): Int =
            (((from shr shift) and 0xff) + (((to shr shift) and 0xff) - ((from shr shift) and 0xff)) * fraction)
                .roundToInt()

        return (channel(from, 16) shl 16) or (channel(from, 8) shl 8) or channel(from, 0)
    }

    private val FORBIDDEN_STANDALONE_TYPES: Set<Int> = setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.SPACE_SEPARATOR.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.UNASSIGNED.toInt(),
    )
}
