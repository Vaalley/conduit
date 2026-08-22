package eu.mctraveler.chat

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.TextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatFeatureJoinTest {
    @Test
    fun `join line includes a green country when one is known`() {
        val line = ChatFeature.joinLine("Alice", "Canada")

        assertEquals("[+] Alice joined from Canada", line.string)
        assertEquals(
            listOf(
                "[" to TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY),
                "+" to TextColor.fromLegacyFormat(ChatFormatting.GREEN),
                "]" to TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY),
                " " to TextColor.fromLegacyFormat(ChatFormatting.GRAY),
                "Alice" to TextColor.fromLegacyFormat(ChatFormatting.GREEN),
                " joined" to TextColor.fromLegacyFormat(ChatFormatting.GRAY),
                " from " to TextColor.fromLegacyFormat(ChatFormatting.GRAY),
                "Canada" to TextColor.fromLegacyFormat(ChatFormatting.GREEN),
            ),
            line.toFlatList(line.style).map { it.string to it.style.color },
        )
    }

    @Test
    fun `join line stays unchanged when no country is known`() {
        val line = ChatFeature.joinLine("Alice")

        assertEquals("[+] Alice joined", line.string)
        assertEquals(
            TextColor.fromLegacyFormat(ChatFormatting.GREEN),
            line.toFlatList(line.style).single { it.string == "Alice" }.style.color,
        )
    }
}
