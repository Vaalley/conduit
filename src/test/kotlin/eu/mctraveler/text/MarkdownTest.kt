package eu.mctraveler.text

import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Donator sign "markdown": `%`-spelled legacy codes, plus the per-viewer
 * `<name>` token that [parse] leaves as a sentinel run for [SignNames] to
 * substitute on the way out.
 */
class MarkdownTest {

    private fun runs(component: Component): List<Pair<String, String?>> =
        component.toFlatList(component.style).map { it.string to it.style.color?.serialize() }

    private fun insertions(component: Component): List<String?> =
        component.toFlatList(component.style).map { it.style.insertion }

    @Test
    fun `percent code becomes a color`() {
        assertEquals(listOf("Green" to "green"), runs(Markdown.parse("%aGreen")))
    }

    @Test
    fun `a name token in a plain line becomes a sentinel run`() {
        val parsed = Markdown.parse("Hi <name>!")
        assertEquals(listOf("Hi " to null, "<name>" to null, "!" to null), runs(parsed))
        assertEquals(listOf<String?>(null, SignNames.SIGN_NAME_SENTINEL, null), insertions(parsed))
    }

    @Test
    fun `a name token carries the active color and only it gets the sentinel`() {
        val parsed = Markdown.parse("%c<name>%r plain")
        assertEquals(listOf("<name>" to "red", " plain" to null), runs(parsed))
        assertEquals(listOf<String?>(SignNames.SIGN_NAME_SENTINEL, null), insertions(parsed))
    }

    @Test
    fun `a bare left angle bracket stays literal`() {
        val parsed = Markdown.parse("a < b <name")
        assertEquals("a < b <name", parsed.string)
        assertTrue(insertions(parsed).all { it == null })
    }

    @Test
    fun `an incomplete token stays literal`() {
        val parsed = Markdown.parse("<name > <Name>")
        assertEquals("<name > <Name>", parsed.string)
        assertTrue(insertions(parsed).all { it == null })
    }

    @Test
    fun `two name tokens each get a sentinel`() {
        val parsed = Markdown.parse("<name> and <name>")
        assertEquals(
            listOf<String?>(SignNames.SIGN_NAME_SENTINEL, null, SignNames.SIGN_NAME_SENTINEL),
            insertions(parsed),
        )
    }

    // A non-privileged editor's `<name>` never reaches [parse]: MixinHooksImpl
    // .markdownLineFor gates the whole markdown path on rank/admin and returns
    // null otherwise, so vanilla stores the literal text unchanged.
    @Test
    fun `plain text round-trips untouched`() {
        assertNull(Markdown.parse("just text").style.color)
        assertEquals("just text", Markdown.parse("just text").string)
    }
}
