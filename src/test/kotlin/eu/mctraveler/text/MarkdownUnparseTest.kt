package eu.mctraveler.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarkdownUnparseTest {

    @Test
    fun codesComeBackAsTyped() {
        for (typed in listOf("%aGreen%r plain", "%c%lBold red", "plain", "%a%l[Wilderness]", "%3Press right-click", "%nunder%r%mstruck")) {
            assertEquals(typed, Markdown.unparse(Markdown.parse(typed)), "round trip of $typed")
        }
    }

    @Test
    fun unparsingNeverChangesWhatTheTextLooksLike() {
        for (typed in listOf("%aGreen %lbold%r after", "%1a%2b%3c", "%oitalic%ccolour then none%r.", "%a%nA%mB%kC")) {
            val parsed = Markdown.parse(typed)
            assertEquals(parsed, Markdown.parse(Markdown.unparse(parsed)), "looks of $typed")
        }
    }

    @Test
    fun theNameTokenStaysATokenAfterTheRoundTrip() {
        val parsed = Markdown.parse("%aHi <name>")
        assertEquals("%aHi <name>", Markdown.unparse(parsed))
        assertEquals(parsed, Markdown.parse(Markdown.unparse(parsed)))
    }
}
