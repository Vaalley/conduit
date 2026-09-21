package eu.mctraveler.text

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Issue #90: Discord-style markdown in chat. */
class ChatMarkdownTest {

    /** A run's text with its styles written as letters: b(old) i(talic) u(nderline) s(trike). */
    private data class Run(val text: String, val flags: String, val click: String? = null)

    private fun runs(raw: String): List<Run> {
        val component = ChatMarkdown.parse(raw)
        return component.toFlatList(component.style)
            .filter { it.string.isNotEmpty() }
            .map {
                val style = it.style
                Run(
                    it.string,
                    buildString {
                        if (style.isBold) append('b')
                        if (style.isItalic) append('i')
                        if (style.isUnderlined) append('u')
                        if (style.isStrikethrough) append('s')
                    },
                    (style.clickEvent as? ClickEvent.OpenUrl)?.uri()?.toString(),
                )
            }
    }

    private fun plain(raw: String): String = ChatMarkdown.parse(raw).string

    // -- the four styles --

    @Test
    fun `each style renders on its own`() {
        assertEquals(listOf(Run("bold", "b")), runs("**bold**"))
        assertEquals(listOf(Run("italic", "i")), runs("*italic*"))
        assertEquals(listOf(Run("underline", "u")), runs("__underline__"))
        assertEquals(listOf(Run("strike", "s")), runs("~~strike~~"))
    }

    @Test
    fun `text around a span stays plain`() {
        assertEquals(
            listOf(Run("say ", ""), Run("this", "b"), Run(" loudly", "")),
            runs("say **this** loudly"),
        )
    }

    @Test
    fun `styles nest`() {
        assertEquals(listOf(Run("both", "bi")), runs("***both***"))
        assertEquals(
            listOf(Run("bold ", "b"), Run("and italic", "bi"), Run(" text", "b")),
            runs("**bold *and italic* text**"),
        )
        assertEquals(listOf(Run("x", "bu")), runs("__**x**__"))
        assertEquals(listOf(Run("all", "biu")), runs("__***all***__"))
        assertEquals(
            listOf(Run("a ", "i"), Run("b", "bi"), Run(" c", "i")),
            runs("*a **b** c*"),
        )
    }

    @Test
    fun `two spans in one line are independent`() {
        assertEquals(
            listOf(Run("a", "b"), Run(" and ", ""), Run("b", "i")),
            runs("**a** and *b*"),
        )
    }

    // -- what is left alone --

    @Test
    fun `a delimiter with no partner stays as typed`() {
        assertEquals("**unclosed", plain("**unclosed"))
        assertEquals(listOf(Run("**unclosed", "")), runs("**unclosed"))
        assertEquals("a ~~ b", plain("a ~~ b"))
        assertEquals("just __ this", plain("just __ this"))
        assertEquals("****", plain("****"))
    }

    @Test
    fun `a delimiter must touch the word it styles`() {
        assertEquals(listOf(Run("2 * 3 * 4", "")), runs("2 * 3 * 4"))
        assertEquals(listOf(Run("* not italic *", "")), runs("* not italic *"))
        assertEquals(listOf(Run("snake__case__name", "")), runs("snake__case__name"))
    }

    @Test
    fun `single underscores and tildes are just characters`() {
        assertEquals(listOf(Run("some_var_name and ~ tilde", "")), runs("some_var_name and ~ tilde"))
    }

    @Test
    fun `a delimiter caught inside a pair does not break it`() {
        assertEquals(listOf(Run("a * b", "b")), runs("**a * b**"))
    }

    @Test
    fun `plain chat and empty chat need no formatting`() {
        assertNull(ChatMarkdown.format("hello there"))
        assertNull(ChatMarkdown.format(""))
        assertNull(ChatMarkdown.format("2 * 3 = 6 and a_b"))
        assertNotNull(ChatMarkdown.format("**hi**"))
    }

    // -- escapes --

    @Test
    fun `a backslash switches a delimiter off and is not shown`() {
        assertEquals(listOf(Run("*expression*", "")), runs("\\*expression*"))
        assertEquals(listOf(Run("*expression*", "")), runs("\\*expression\\*"))
        assertEquals("__not underlined__", plain("\\_\\_not underlined\\_\\_"))
        assertEquals("~~kept~~", plain("\\~\\~kept\\~\\~"))
    }

    @Test
    fun `an escaped delimiter still lets the rest of the line format`() {
        assertEquals(
            listOf(Run("*x* ", ""), Run("bold", "b")),
            runs("\\*x* **bold**"),
        )
    }

    @Test
    fun `a backslash before a backslash is one backslash`() {
        assertEquals(listOf(Run("\\", ""), Run("it", "i")), runs("\\\\*it*"))
    }

    @Test
    fun `a backslash before anything else is left alone`() {
        assertEquals("a\\b and C:\\dir", plain("a\\b and C:\\dir"))
        assertNull(ChatMarkdown.format("a\\b"))
    }

    @Test
    fun `escaping counts as formatting so the backslash is dropped`() {
        assertNotNull(ChatMarkdown.format("\\*x*"))
    }

    // -- links --

    @Test
    fun `a link is underlined and clickable`() {
        val link = runs("see https://example.com/page ok").single { it.click != null }
        assertEquals("https://example.com/page", link.text)
        assertEquals("u", link.flags)
        assertEquals("https://example.com/page", link.click)
        assertEquals("see https://example.com/page ok", plain("see https://example.com/page ok"))
    }

    @Test
    fun `markdown inside a link is left alone`() {
        assertEquals(
            listOf(Run("https://example.com/a__b__c*d*e", "u", "https://example.com/a__b__c*d*e")),
            runs("https://example.com/a__b__c*d*e"),
        )
        assertEquals(
            listOf(Run("https://example.com/~user/a_b_c", "u", "https://example.com/~user/a_b_c")),
            runs("https://example.com/~user/a_b_c"),
        )
    }

    @Test
    fun `markdown around a link still applies to it`() {
        assertEquals(
            listOf(Run("https://example.com", "bu", "https://example.com")),
            runs("**https://example.com**"),
        )
        assertEquals(
            listOf(Run("read ", "i"), Run("https://example.com/x", "iu", "https://example.com/x"), Run(" now", "i")),
            runs("*read https://example.com/x now*"),
        )
    }

    @Test
    fun `punctuation after a link is not part of it`() {
        assertEquals("https://example.com/a", runs("(https://example.com/a).").single { it.click != null }.text)
        assertEquals("https://example.com/a", runs("go to https://example.com/a, then").single { it.click != null }.text)
        assertEquals("https://example.com/a", runs("really? https://example.com/a!").single { it.click != null }.text)
    }

    @Test
    fun `balanced parentheses stay in the link`() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Foo_(bar)",
            runs("https://en.wikipedia.org/wiki/Foo_(bar)").single { it.click != null }.text,
        )
    }

    @Test
    fun `only http and https with a host are links`() {
        for (text in listOf("javascript:alert(1)", "ftp://example.com/x", "file:///etc/passwd", "https://", "http:// x")) {
            assertTrue(runs(text).none { it.click != null }, text)
        }
        assertTrue(runs("xhttps://example.com").none { it.click != null })
        val shouty = runs("HTTPS://EXAMPLE.COM/x").single { it.click != null }
        assertEquals("HTTPS://EXAMPLE.COM/x", shouty.text)
        assertEquals("https://EXAMPLE.COM/x", shouty.click)
    }

    @Test
    fun `a backslash does not turn a link into markdown or back`() {
        // The backslash is not escapable text here, and a URI cannot hold one, so this is no link.
        assertTrue(runs("https://example.com/a\\b").none { it.click != null })
    }

    // -- the rendered text --

    @Test
    fun `the visible text drops only the delimiters that formatted something`() {
        assertEquals("bold italic under strike", plain("**bold** *italic* __under__ ~~strike~~"))
        assertEquals("a * b ** c", plain("a * b ** c"))
        assertTrue(ChatMarkdown.parse("**x**") is Component)
    }
}
