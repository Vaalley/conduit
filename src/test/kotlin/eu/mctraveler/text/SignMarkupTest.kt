package eu.mctraveler.text

import eu.mctraveler.MinecraftTestBootstrap
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class SignMarkupTest {

    @Test
    fun `plain text renders as one unstyled component`() {
        val result = SignMarkup.render("Welcome")

        assertEquals("Welcome", result.component.string)
        assertTrue(result.component.style.isEmpty)
        assertEquals(emptyList<SignMarkupProblem>(), result.problems)
        assertEquals(false, result.hadMarkup)
        assertEquals(result.component.string, SignMarkup.strip("Welcome"))
    }

    @Test
    fun `named color and nested decoration produce styled spans`() {
        val result = SignMarkup.render("<red>Stop <bold>now</bold></red>")

        assertEquals("Stop now", result.component.string)
        assertEquals(
            listOf(
                "Stop " to TextColor.fromLegacyFormat(ChatFormatting.RED) to false,
                "now" to TextColor.fromLegacyFormat(ChatFormatting.RED) to true,
            ),
            result.component.toFlatList().map {
                it.string to it.style.color to it.style.isBold
            },
        )
        assertTrue(result.hadMarkup)
        assertEquals(emptyList<SignMarkupProblem>(), result.problems)
        assertEquals("Stop now", SignMarkup.strip("<red>Stop <bold>now</bold></red>"))
    }

    @Test
    fun `legacy color clears prior decoration and reset returns to plain style`() {
        val result = SignMarkup.render("<bold>Bold &cRed</bold><reset>Plain")

        assertEquals(
            listOf(
                "Bold " to true to null,
                "Red" to false to TextColor.fromLegacyFormat(ChatFormatting.RED),
                "Plain" to false to null,
            ),
            result.component.toFlatList().map {
                it.string to it.style.isBold to it.style.color
            },
        )
        assertEquals("Bold RedPlain", result.component.string)
        assertFalse(result.problems.isNotEmpty())
    }

    @Test
    fun `all vanilla color names and the grey alias are accepted`() {
        val names = listOf(
            "black" to "black",
            "dark_blue" to "dark_blue",
            "dark_green" to "dark_green",
            "dark_aqua" to "dark_aqua",
            "dark_red" to "dark_red",
            "dark_purple" to "dark_purple",
            "gold" to "gold",
            "gray" to "gray",
            "grey" to "gray",
            "dark_gray" to "dark_gray",
            "blue" to "blue",
            "green" to "green",
            "aqua" to "aqua",
            "red" to "red",
            "light_purple" to "light_purple",
            "yellow" to "yellow",
            "white" to "white",
        )

        for ((name, expected) in names) {
            val result = SignMarkup.render("<$name>x</$name>")

            assertEquals(expected, result.component.toFlatList().single().style.color?.serialize())
            assertEquals(emptyList<SignMarkupProblem>(), result.problems)
        }
    }

    @Test
    fun `legacy decoration codes compose and reset`() {
        val result = SignMarkup.render("&lB &oI &nU &mS &kO &rR")

        assertEquals(
            listOf(
                "B " to true to false to false to false to false,
                "I " to true to true to false to false to false,
                "U " to true to true to true to false to false,
                "S " to true to true to true to true to false,
                "O " to true to true to true to true to true,
                "R" to false to false to false to false to false,
            ),
            result.component.toFlatList().map {
                it.string to it.style.isBold to it.style.isItalic to
                    it.style.isUnderlined to it.style.isStrikethrough to it.style.isObfuscated
            },
        )
    }

    @Test
    fun `legacy hex colors use the six hexadecimal digits after the hash`() {
        val result = SignMarkup.render("&#12abefhex")

        assertEquals("hex", result.component.string)
        assertEquals(0x12abef, result.component.style.color?.value)
    }

    @Test
    fun `escaped markup is emitted literally`() {
        val source = "\\<red>literal\\</red> \\&c \\\\"
        val result = SignMarkup.render(source)

        assertEquals("<red>literal</red> &c \\", result.component.string)
        assertEquals(result.component.string, SignMarkup.strip(source))
        assertTrue(result.hadMarkup)
    }

    @Test
    fun `gradient interpolates explicit sRGB colors per character`() {
        val result = SignMarkup.render("<gradient:#ff0000:#0000ff>ABCD</gradient>")

        assertEquals(
            listOf(
                "A" to 0xff0000,
                "B" to 0xaa0055,
                "C" to 0x5500aa,
                "D" to 0x0000ff,
            ),
            result.component.toFlatList().map { it.string to it.style.color?.value },
        )
        assertEquals("ABCD", SignMarkup.strip("<gradient:#ff0000:#0000ff>ABCD</gradient>"))
    }

    @Test
    fun `gradient continues across nested decoration spans`() {
        val result = SignMarkup.render("<gradient:#ff0000:#0000ff>AB<bold>CD</bold></gradient>")

        assertEquals(
            listOf(
                "A" to 0xff0000 to false,
                "B" to 0xaa0055 to false,
                "C" to 0x5500aa to true,
                "D" to 0x0000ff to true,
            ),
            result.component.toFlatList().map {
                it.string to it.style.color?.value to it.style.isBold
            },
        )
    }

    @Test
    fun `rainbow uses a full HSV cycle with an optional hue offset`() {
        val result = SignMarkup.render("<rainbow:90>ABCD</rainbow>")

        assertEquals(
            listOf(
                0x80ff00,
                0x00ffff,
                0x8000ff,
                0xff0000,
            ),
            result.component.toFlatList().map { it.style.color?.value },
        )
    }

    @Test
    fun `rainbow continues across nested decoration spans`() {
        val result = SignMarkup.render("<rainbow>AB<bold>CD</bold></rainbow>")

        assertEquals(
            listOf(
                "A" to 0xff0000 to false,
                "B" to 0x80ff00 to false,
                "C" to 0x00ffff to true,
                "D" to 0x8000ff to true,
            ),
            result.component.toFlatList().map {
                it.string to it.style.color?.value to it.style.isBold
            },
        )
    }

    @Test
    fun `font tags use the vanilla resource descriptions`() {
        val result = SignMarkup.render("<sga>S</sga><illager>I</illager><enchant>E</enchant>")
        val fonts = result.component.toFlatList().map { it.style.font }

        assertEquals(
            listOf(
                FontDescription.Resource(Identifier.parse("minecraft:alt")),
                FontDescription.Resource(Identifier.parse("minecraft:illageralt")),
                FontDescription.Resource(Identifier.parse("minecraft:alt")),
            ),
            fonts,
        )
        assertEquals(false, result.component.toFlatList()[0].style.isObfuscated)
        assertEquals(true, result.component.toFlatList()[2].style.isObfuscated)
    }

    @Test
    fun `malformed markup remains visible and reports a problem`() {
        val result = SignMarkup.render("Keep <gradient:#ff0000>this")

        assertEquals("Keep <gradient:#ff0000>this", result.component.string)
        assertEquals(1, result.problems.size)
        assertEquals("unknown or malformed tag", result.problems.single().message)
        assertEquals(result.component.string, SignMarkup.strip("Keep <gradient:#ff0000>this"))
    }

    @Test
    fun `component cap falls back to the stripped reading`() {
        val source = "<rainbow>ABCD</rainbow>"
        val result = SignMarkup.render(
            source,
            SignMarkupLimits(maxComponentsPerLine = 3),
        )

        assertEquals("ABCD", result.component.string)
        assertTrue(result.component.style.isEmpty)
        assertEquals("component cap exceeded", result.problems.single().message)
        assertEquals(result.component.string, SignMarkup.strip(source))
    }

    @Test
    fun `closing shorthand restores the innermost style`() {
        val result = SignMarkup.render("<red><bold>x</>y</>")

        assertEquals(
            listOf(
                "x" to TextColor.fromLegacyFormat(ChatFormatting.RED) to true,
                "y" to TextColor.fromLegacyFormat(ChatFormatting.RED) to false,
            ),
            result.component.toFlatList().map {
                it.string to it.style.color to it.style.isBold
            },
        )
    }

    @Test
    fun `unclosed tags apply through the end without a problem`() {
        val result = SignMarkup.render("<italic>still styled")

        assertEquals("still styled", result.component.string)
        assertEquals(true, result.component.toFlatList().single().style.isItalic)
        assertEquals(emptyList<SignMarkupProblem>(), result.problems)
    }

    @Test
    fun `literal section signs are removed without applying their following code`() {
        val result = SignMarkup.render("A§cB")

        assertEquals("AcB", result.component.string)
        assertTrue(result.component.toFlatList().all { it.style.color == null })
    }

    @Test
    fun `closing with nothing open is ignored and reported`() {
        val result = SignMarkup.render("x</missing>y")

        assertEquals("x</missing>y", result.component.string)
        assertEquals("closing tag with nothing open", result.problems.single().message)
    }

    @Test
    fun `mismatched closing tags remain visible and are reported`() {
        val result = SignMarkup.render("<red><bold>x</red>y")

        assertEquals("x</red>y", result.component.string)
        assertEquals("closing tag does not match the open tag", result.problems.single().message)
    }

    @Test
    fun `strip agrees with the rendered readable text`() {
        val sources = listOf(
            "",
            "plain",
            "<red>red</red>",
            "&lbold &rplain",
            "<gradient:#ff0000:#0000ff>gradient</gradient>",
            "<rainbow>rainbow</rainbow>",
            "\\<literal> \\&c",
            "bad <unknown>tag</unknown>",
            "bad <gradient:#ff0000>tag",
        )

        for (source in sources) {
            assertEquals(SignMarkup.render(source).component.string, SignMarkup.strip(source))
        }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() {
            MinecraftTestBootstrap.ensure()
        }
    }
}
