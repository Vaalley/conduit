package eu.mctraveler.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatSelectorTest {
    @Test
    fun `parsePlayers splits comma separated names and trims whitespace`() {
        assertEquals(listOf("Alice", "Bob", "Carol"), ChatSelector.parsePlayers(" Alice, Bob ,,Carol "))
    }
}
