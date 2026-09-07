package eu.mctraveler.text

import eu.mctraveler.MinecraftTestBootstrap
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.entity.SignText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class SignNamesTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun boot() = MinecraftTestBootstrap.ensure()
    }

    private fun line0(text: SignText) =
        text.getMessage(0, false).let { c -> c.toFlatList(c.style) }
            .map { it.string to it.style.insertion }

    @Test
    fun `personalize replaces the sentinel run with the viewer name and drops the insertion`() {
        val text = SignText().setMessage(0, Markdown.parse("%aHi <name>!"))
        assertTrue(SignNames.hasToken(text))

        val personalized = SignNames.personalize(text, "Alice")!!
        assertEquals(
            listOf("Hi " to null, "Alice" to null, "!" to null),
            line0(personalized),
        )
        // The green color from `%a` still rides the run…
        val alice = personalized.getMessage(0, false).toFlatList(personalized.getMessage(0, false).style)[1]
        assertEquals("green", alice.style.color?.serialize())
        // …and the sentinel is gone, so a re-scan finds nothing.
        assertFalse(SignNames.hasToken(personalized))
    }

    @Test
    fun `each viewer gets their own name`() {
        val text = SignText().setMessage(0, Markdown.parse("<name>"))
        assertEquals("Bob", SignNames.personalize(text, "Bob")!!.getMessage(0, false).string)
        assertEquals("Carol", SignNames.personalize(text, "Carol")!!.getMessage(0, false).string)
    }

    @Test
    fun `a token-free sign personalizes to null`() {
        val text = SignText()
            .setMessage(0, Component.literal("plain"))
            .setMessage(1, Markdown.parse("%agreen"))
        assertFalse(SignNames.hasToken(text))
        assertNull(SignNames.personalize(text, "Alice"))
    }

    @Test
    fun `a token on the back text is detected`() {
        val front = SignText()
        val back = SignText().setMessage(2, Markdown.parse("<name>"))
        assertFalse(SignNames.hasToken(front))
        assertTrue(SignNames.hasToken(back))
    }
}
