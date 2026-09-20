package eu.mctraveler.help

import eu.mctraveler.text.Paint
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * Turns a [HelpIndex] and a `/help` argument into the message the player reads.
 * The house layout is the issue's: a yellow struck-through header, the content,
 * and (for the list) a page footer.
 */
class HelpPages<S>(private val index: HelpIndex<S>) {

    /** The reply to `/help [topic]`; errors are [Paint.error] lines, and [isError] says which. */
    fun render(raw: String?): Reply {
        val topic = raw?.trim().orEmpty()
        if (topic.isEmpty()) return Reply(listPage(1))
        val word = topic.substringBefore(' ')
        if (NUMBER.matches(word)) {
            val number = word.toIntOrNull()
            return if (number != null && number in 1..index.pageCount) Reply(listPage(number))
            else Reply(pageError(word), isError = true)
        }
        if (word.equals(MARKDOWN, ignoreCase = true)) return Reply(markdownPage())
        val entry = index.find(word) ?: return Reply(unknownCommand(word), isError = true)
        return Reply(commandPage(entry))
    }

    class Reply(val message: Component, val isError: Boolean = false)

    // -- the list --

    fun listPage(page: Int): Component {
        val lines = mutableListOf<Component>(
            header(),
            Paint.yellow.italic("Type /help <command> to find out what a specific command does."),
        )
        index.page(page).mapTo(lines, ::commandLine)
        lines += footer(page)
        return joinLines(lines)
    }

    /** `/name <required> [optional]`, the name a button that opens the command's page. */
    private fun commandLine(entry: HelpEntry<S>): Component {
        val name = Paint.white
            .runs("/help ${entry.name}")
            .hover(Paint.yellow("Click to see what /${entry.name} does"))("/${entry.name}")
        val tokens = index.syntax(entry).split(' ').drop(1)
        return Paint(name, *spaced(tokens.map(::syntaxToken)))
    }

    private fun footer(current: Int): Component {
        val numbers = (1..index.pageCount).map { page ->
            if (page == current) Paint.yellow.bold.underline(page)
            else Paint.yellow.runs("/help $page").hover(Paint.yellow("Go to page $page"))(page)
        }
        val parts = mutableListOf<Any?>(strike(), Paint.yellow(" ( Page "))
        numbers.forEachIndexed { i, number ->
            if (i > 0) parts += Paint.yellow(" ")
            parts += number
        }
        parts += Paint.yellow(" ) ")
        parts += strike()
        return Paint(*parts.toTypedArray())
    }

    // -- one command --

    private fun commandPage(entry: HelpEntry<S>): Component {
        val lines = mutableListOf<Component>(header(), Paint.white(entry.namesLine))
        val options = index.options(entry)
        if (options.isNotEmpty()) lines += Paint.gray("Available options are: ${options.joinToString(", ")}")
        val info = entry.info
        if (info != null) {
            info.description.split('\n').mapTo(lines) { Component.literal(it) }
        } else {
            val usage = index.usageLines(entry)
            usage.take(MAX_USAGE_LINES).mapTo(lines) { usageLine(entry.name, it) }
            if (usage.size > MAX_USAGE_LINES) {
                lines += Paint.gray("... and ${usage.size - MAX_USAGE_LINES} more forms")
            }
            lines += Paint.gray.italic("No description is available for this command.")
        }
        return joinLines(lines)
    }

    private fun usageLine(name: String, usage: String): Component {
        val tokens = usage.split(' ').filter(String::isNotEmpty)
        return Paint(Paint.white("/$name"), *spaced(tokens.map(::syntaxToken)))
    }

    // -- markdown --

    fun markdownPage(): Component {
        val lines = mutableListOf<Component>(
            header(),
            Paint.yellow.italic("Type a % code before your text on a sign. It stays until the next colour or %r."),
            Paint.gray("Colours"),
        )
        MARKDOWN_COLORS.chunked(4).mapTo(lines) { row -> codeRow(row) }
        lines += Paint.gray("Decorations")
        lines += codeRow(MARKDOWN_DECORATIONS)
        lines += Paint.gray("Reset")
        lines += codeRow(listOf(MARKDOWN_RESET))
        lines += Paint.gray(
            "Markdown on signs is available to Donators and admins. Writing <name> on a sign shows " +
                "each reader their own name.",
        )
        return joinLines(lines)
    }

    private fun codeRow(codes: List<Char>): Component =
        Paint(*spaced(codes.map(::codeSample), separator = "   ", leading = false))

    // -- errors --

    private fun unknownCommand(word: String): Component = Paint.error(
        "Unknown command ", Paint.red("/" + word.removePrefix("/")), ". Use /help to see the commands you can use.",
    )

    private fun pageError(word: String): Component = Paint.error(
        "Page ", Paint.red(word), " does not exist. ",
        if (index.pageCount == 1) "There is only 1 page." else "Choose a page between 1 and ${index.pageCount}.",
    )

    // -- building blocks --

    private fun header(): Component = Paint(strike(), Paint.yellow("( Help menu )"), strike())

    private fun strike(): Component = Paint.yellow.strikethrough(STRIKE)

    private fun joinLines(lines: List<Component>): Component {
        val result = Component.empty()
        lines.forEachIndexed { i, line ->
            if (i > 0) result.append(Component.literal("\n"))
            result.append(line)
        }
        return result
    }

    /** [parts] separated by [separator]; with [leading] the first one is preceded by it too. */
    private fun spaced(parts: List<Component>, separator: String = " ", leading: Boolean = true): Array<Any?> {
        val result = mutableListOf<Any?>()
        parts.forEachIndexed { i, part ->
            if (leading || i > 0) result += separator
            result += part
        }
        return result.toTypedArray()
    }

    companion object {
        const val STRIKE = "            "
        const val MARKDOWN = "markdown"
        const val MAX_USAGE_LINES = 10

        private val NUMBER = Regex("-?[0-9]+")

        val MARKDOWN_COLORS: List<Char> = "0123456789abcdef".toList()
        val MARKDOWN_DECORATIONS: List<Char> = "klmno".toList()
        const val MARKDOWN_RESET = 'r'

        /** `<x>` gray (the issue's light_gray), `[x]` dark gray, a plain word gray. */
        fun syntaxToken(token: String): MutableComponent =
            if (token.startsWith("[")) Paint.darkGray(token) else Paint.gray(token)

        /** `%a green`, rendered in the very style the code produces. */
        fun codeSample(code: Char): MutableComponent {
            val format = ChatFormatting.getByCode(code) ?: error("no formatting for %$code")
            return Component.literal("%$code ${format.name.lowercase()}").withStyle(format)
        }
    }
}
