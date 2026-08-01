package eu.mctraveler.text

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

/**
 * The server's message design language: a small DSL over vanilla text [Component]s,
 * porting the Portal's Paint (feature-api/paint.ts).
 *
 * Styles chain and then apply to content via invocation:
 *
 * ```kotlin
 * Paint.green("MCTraveler")
 * Paint.red.bold("Red and bold")
 * Paint.gray("Player ", Paint.red(name), " not found or is offline")
 * ```
 *
 * Content arguments may be strings, other [Component]s (nesting), or any value
 * (rendered via `toString()`); nulls and empty strings are dropped.
 */
class Paint private constructor(private val style: Style) {

    // The Portal's exact color vocabulary.
    val green: Paint get() = Paint(style.withColor(ChatFormatting.GREEN))
    val gray: Paint get() = Paint(style.withColor(ChatFormatting.GRAY))
    val white: Paint get() = Paint(style.withColor(ChatFormatting.WHITE))
    val yellow: Paint get() = Paint(style.withColor(ChatFormatting.YELLOW))
    val red: Paint get() = Paint(style.withColor(ChatFormatting.RED))
    val blue: Paint get() = Paint(style.withColor(ChatFormatting.BLUE))
    val darkGray: Paint get() = Paint(style.withColor(ChatFormatting.DARK_GRAY))

    // Nucleus's two extra colors. They began as prefix-only (the Portal's Paint
    // had neither), and became content colors when Nucleus's clickable "here"
    // arrived: aqua in the admin back-link, gold in the delete confirmation and
    // the Teleportation Crystal's charge-capacity lore line (spec deviation 10).
    val aqua: Paint get() = Paint(style.withColor(ChatFormatting.AQUA))
    val gold: Paint get() = Paint(style.withColor(ChatFormatting.GOLD))

    /** No explicit color: the text inherits its surroundings' (default white at top level). */
    val reset: Paint get() = Paint(style.withColor(null as TextColor?))

    // The Portal's decorations.
    val bold: Paint get() = Paint(style.withBold(true))
    val italic: Paint get() = Paint(style.withItalic(true))
    val underline: Paint get() = Paint(style.withUnderlined(true))

    /**
     * Struck through. Beyond the Portal's Paint vocabulary, which had no
     * strikethrough — its one struck-through string (the region sidebar's
     * separator line) was hand-built as raw NBT instead.
     */
    val strikethrough: Paint get() = Paint(style.withStrikethrough(true))

    /**
     * Clickable: running [command] as the reader, the way the client runs a
     * chat suggestion. Part of the style chain, so it composes with a color —
     * `Paint.gold.runs("/embassy delete Home")("here")` — and applies to the
     * content it wraps rather than the whole line.
     */
    fun runs(command: String): Paint = Paint(style.withClickEvent(ClickEvent.RunCommand(command)))

    /** Builds a component carrying this chain's style over the given content. */
    operator fun invoke(vararg content: Any?): MutableComponent {
        val parts = content.mapNotNull(::toPart)
        return when (parts.size) {
            0 -> Component.empty()
            1 -> parts[0].copy().also { it.style = it.style.applyTo(style) }
            else -> parts.fold(Component.empty().withStyle(style), MutableComponent::append)
        }
    }

    private fun toPart(item: Any?): Component? = when (item) {
        null -> null
        is Component -> item
        else -> item.toString().takeIf(String::isNotEmpty)?.let(Component::literal)
    }

    companion object {
        private val plain = Paint(Style.EMPTY)

        val green: Paint get() = plain.green
        val gray: Paint get() = plain.gray
        val white: Paint get() = plain.white
        val yellow: Paint get() = plain.yellow
        val red: Paint get() = plain.red
        val blue: Paint get() = plain.blue
        val darkGray: Paint get() = plain.darkGray
        val aqua: Paint get() = plain.aqua
        val gold: Paint get() = plain.gold
        val reset: Paint get() = plain.reset
        val bold: Paint get() = plain.bold
        val italic: Paint get() = plain.italic
        val underline: Paint get() = plain.underline
        val strikethrough: Paint get() = plain.strikethrough

        fun runs(command: String): Paint = plain.runs(command)

        /** Unstyled composition: `Paint("Hello ", name)`. */
        operator fun invoke(vararg content: Any?): MutableComponent = plain(*content)

        // The Portal's semantic helpers: a bold colored prefix word, a plain space,
        // then the content in gray — the shape every ERROR/SUCCESS/USAGE message has.

        /** `ERROR <content>`: red+bold prefix, gray content. */
        fun error(vararg content: Any?): MutableComponent = prefixed(red.bold("ERROR"), content)

        /** `SUCCESS <content>`: green+bold prefix, gray content. */
        fun success(vararg content: Any?): MutableComponent = prefixed(green.bold("SUCCESS"), content)

        /** `USAGE <content>`: aqua+bold prefix, gray content (the Portal's §b§lUSAGE §7). */
        fun usage(vararg content: Any?): MutableComponent = prefixed(aqua.bold("USAGE"), content)

        // INFO and WARNING joined the vocabulary with the Nucleus-era features
        // (spec deviation 10), in Nucleus's exact prefix colors.

        /** `INFO <content>`: aqua+bold prefix, gray content. */
        fun info(vararg content: Any?): MutableComponent = prefixed(aqua.bold("INFO"), content)

        /**
         * `WARNING <content>`: gold+bold prefix, gray content. INFO's other
         * half; its first caller is `/embassy delete`'s confirmation prompt.
         */
        fun warning(vararg content: Any?): MutableComponent = prefixed(gold.bold("WARNING"), content)

        private fun prefixed(prefix: Component, content: Array<out Any?>): MutableComponent =
            plain(prefix, " ", gray(*content))
    }
}
