package eu.mctraveler.cosmetic

import net.minecraft.world.scores.TeamColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The colours `/locator-color` accepts (issue #84). */
class LocatorColorTest {

    @Test
    fun `every Minecraft colour name is accepted`() {
        for (color in TeamColor.VALUES) {
            assertEquals(color.rgb(), LocatorColor.parse(color.serializedName), color.serializedName)
        }
    }

    @Test
    fun `names ignore case, spaces and dashes`() {
        assertEquals(TeamColor.LIGHT_PURPLE.rgb(), LocatorColor.parse("Light_Purple"))
        assertEquals(TeamColor.LIGHT_PURPLE.rgb(), LocatorColor.parse("light purple"))
        assertEquals(TeamColor.DARK_AQUA.rgb(), LocatorColor.parse("dark-aqua"))
        assertEquals(TeamColor.GOLD.rgb(), LocatorColor.parse("  GOLD "))
    }

    @Test
    fun `hex is accepted with or without the hash, in six or three digits`() {
        assertEquals(0xFF8800, LocatorColor.parse("#ff8800"))
        assertEquals(0xFF8800, LocatorColor.parse("FF8800"))
        assertEquals(0xFF8800, LocatorColor.parse("#f80"))
        assertEquals(0x00AAFF, LocatorColor.parse("0af"))
        assertEquals(0x000000, LocatorColor.parse("#000000"))
    }

    @Test
    fun `anything else is refused`() {
        for (bad in listOf("", "#", "notacolour", "#ff88", "#ff88000", "#gggggg", "12345678", "reset", "#ff 88 00")) {
            assertNull(LocatorColor.parse(bad), "'$bad'")
        }
    }
}
