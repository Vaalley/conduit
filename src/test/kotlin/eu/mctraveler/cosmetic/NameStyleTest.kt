package eu.mctraveler.cosmetic

import eu.mctraveler.text.Paint
import net.minecraft.network.chat.TextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NameStyleTest {
    private val rankColor = Paint.green

    @Test
    fun `parseHex accepts six digit colors with optional hash`() {
        assertEquals(0xff8800, NameStyle.parseHex("#FF8800"))
        assertEquals(0xff8800, NameStyle.parseHex("ff8800"))
        assertNull(NameStyle.parseHex("#zzz"))
        assertNull(NameStyle.parseHex("#ff88000"))
    }

    @Test
    fun `validateTag accepts one grapheme and rejects unsafe input`() {
        assertEquals("🐢", NameStyle.validateTag("🐢"))
        assertEquals("★", NameStyle.validateTag("★"))
        assertEquals("A", NameStyle.validateTag("A"))
        assertEquals("👨‍👩‍👧", NameStyle.validateTag("👨‍👩‍👧"))
        assertNull(NameStyle.validateTag(""))
        assertNull(NameStyle.validateTag(" "))
        assertNull(NameStyle.validateTag("ab"))
        assertNull(NameStyle.validateTag("§c"))
        assertNull(NameStyle.validateTag("\u200b"))
        assertNull(NameStyle.validateTag("\u0301"))
    }

    @Test
    fun `gradient renders one colored component per character`() {
        val rendered = NameStyle.render(
            "Val",
            NameStyle.Style(null, null, 0x000000 to 0xffffff, false, rankColor),
        )
        assertEquals("Val", rendered.string)
        assertEquals(3, rendered.siblings.size)
        assertEquals(TextColor.fromRgb(0x000000), rendered.siblings[0].style.color)
        assertEquals(TextColor.fromRgb(0x808080), rendered.siblings[1].style.color)
        assertEquals(TextColor.fromRgb(0xffffff), rendered.siblings[2].style.color)
    }

    @Test
    fun `solid color applies to the whole name`() {
        val rendered = NameStyle.render(
            "Val",
            NameStyle.Style(null, 0xff8800, null, false, rankColor),
        )
        assertEquals(TextColor.fromRgb(0xff8800), rendered.siblings.single().style.color)
    }

    @Test
    fun `donator marker and tag precede the name`() {
        val rendered = NameStyle.render(
            "Vaalley",
            NameStyle.Style("🐢", null, null, true, rankColor),
        )
        assertEquals("🐢 ✦ Vaalley", rendered.string)
        assertEquals(TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.WHITE), rendered.siblings[0].style.color)
        assertEquals(
            TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.GOLD),
            rendered.siblings[1].style.color,
        )
        assertEquals("✦ ", rendered.siblings[1].string)
    }

    @Test
    fun `rank color is used without cosmetic color`() {
        val rendered = NameStyle.render(
            "Vaalley",
            NameStyle.Style(null, null, null, false, rankColor),
        )
        assertEquals("green", rendered.siblings.single().style.color?.serialize())
    }
}
