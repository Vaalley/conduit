package eu.mctraveler.text

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.TextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The text DSL is the port of the Portal's Paint (feature-api/paint.ts) — the server's
 * entire message design language. Cases are mined from the Portal's paint tests
 * (test/feature-api/paint.test.ts), asserted as component structure rather than legacy
 * §-strings, per Intent Parity.
 */
class PaintTest {

    // --- Plain and colored text (Portal: "simple text", one test per color) ---

    @Test
    fun `plain text has no styling`() {
        val component = Paint("Hello World")
        assertEquals("Hello World", component.string)
        assertEquals(true, component.style.isEmpty)
    }

    @Test
    fun `each color in the vocabulary maps to its vanilla color`() {
        // Expected names are the Portal's nbtColorMapping literals (paint.ts).
        val cases = mapOf(
            Paint.green("Green text") to "green",
            Paint.gray("Gray") to "gray",
            Paint.white("White") to "white",
            Paint.yellow("Yellow") to "yellow",
            Paint.red("Red text") to "red",
            Paint.blue("Blue text") to "blue",
            Paint.darkGray("Dark") to "dark_gray",
        )
        for ((component, colorName) in cases) {
            assertEquals(colorName, component.style.color?.serialize())
        }
    }

    @Test
    fun `colored text keeps its content`() {
        assertEquals("Green text", Paint.green("Green text").string)
    }

    @Test
    fun `reset produces uncolored text`() {
        val component = Paint.reset("Reset")
        assertEquals("Reset", component.string)
        assertNull(component.style.color)
    }

    // --- Decorations (Portal: "bold text", "italic text", "underline text") ---

    @Test
    fun `bold text`() {
        val component = Paint.bold("Bold text")
        assertEquals("Bold text", component.string)
        assertEquals(true, component.style.isBold)
        assertNull(component.style.color)
    }

    @Test
    fun `italic text`() {
        assertEquals(true, Paint.italic("Italic text").style.isItalic)
    }

    @Test
    fun `underlined text`() {
        assertEquals(true, Paint.underline("Underlined").style.isUnderlined)
    }

    // --- Chaining (Portal: "colored and bold", "blue with bold") ---

    @Test
    fun `color and decoration chain`() {
        val component = Paint.red.bold("Red and bold")
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), component.style.color)
        assertEquals(true, component.style.isBold)
        assertEquals("Red and bold", component.string)
    }

    @Test
    fun `chaining works in either order and accumulates decorations`() {
        val component = Paint.bold.underline.blue("Test")
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.BLUE), component.style.color)
        assertEquals(true, component.style.isBold)
        assertEquals(true, component.style.isUnderlined)
    }

    @Test
    fun `a later color in the chain wins`() {
        val component = Paint.red.green("text")
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN), component.style.color)
    }

    // --- Nesting and multi-part composition (Portal: "nested paint objects",
    // "template literal with values" — §c This is §a green §r§c text) ---

    @Test
    fun `styled child nests inside a styled parent`() {
        val component = Paint.red("This is ", Paint.green("green"), " text")
        assertEquals("This is green text", component.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), component.style.color)
        assertEquals(3, component.siblings.size)
        assertEquals(
            TextColor.fromLegacyFormat(ChatFormatting.GREEN),
            component.siblings[1].style.color,
        )
    }

    @Test
    fun `parent style is re-applied to text after a styled child`() {
        val component = Paint.red("This is ", Paint.green("green"), " text")
        // Resolve each part's rendered style the way the client does (style inheritance).
        val rendered = component.toFlatList(component.style).map { it.string to it.style.color }
        assertEquals(
            listOf(
                "This is " to TextColor.fromLegacyFormat(ChatFormatting.RED),
                "green" to TextColor.fromLegacyFormat(ChatFormatting.GREEN),
                " text" to TextColor.fromLegacyFormat(ChatFormatting.RED),
            ),
            rendered,
        )
    }

    @Test
    fun `interpolated values compose into a styled root`() {
        val value = "World"
        val component = Paint.yellow("Hello ", value, "!")
        assertEquals("Hello World!", component.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.YELLOW), component.style.color)
    }

    @Test
    fun `non-string values render via toString`() {
        assertEquals("You have 5 items", Paint.gray("You have ", 5, " items").string)
    }

    @Test
    fun `null and empty content parts are dropped`() {
        val component = Paint.red("a", null, "", "b")
        assertEquals("ab", component.string)
        assertEquals(2, component.siblings.size)
    }

    @Test
    fun `no content yields an empty unstyled component`() {
        val component = Paint.red()
        assertEquals("", component.string)
        assertEquals(true, component.style.isEmpty)
    }

    // --- Single-part collapse (Portal: toNbtObject single-part shape) ---

    @Test
    fun `single content collapses to one part with the style applied directly`() {
        val component = Paint.green("Green text")
        assertEquals(0, component.siblings.size)
    }

    @Test
    fun `a nested child's own style survives a single-part collapse`() {
        // Intent Parity: the Portal's legacy renderer kept the child's color here
        // (§c§ax renders green); its NBT path clobbered it — a bug we don't keep.
        val component = Paint.red(Paint.green("x"))
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN), component.style.color)
    }

    @Test
    fun `a collapsed child inherits the parent's unset style fields`() {
        val component = Paint.red.bold(Paint.green("x"))
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN), component.style.color)
        assertEquals(true, component.style.isBold)
    }

    // --- Semantic helpers (Portal: "error helper", "usage helper", success symmetry) ---

    @Test
    fun `error helper renders red-bold ERROR then gray content`() {
        val component = Paint.error("Something went wrong")
        assertEquals("ERROR Something went wrong", component.string)
        assertEquals(true, component.style.isEmpty)

        val (prefix, space, content) = component.siblings
        assertEquals("ERROR", prefix.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), prefix.style.color)
        assertEquals(true, prefix.style.isBold)
        assertEquals(" ", space.string)
        assertEquals(true, space.style.isEmpty)
        assertEquals("Something went wrong", content.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), content.style.color)
        assertEquals(false, content.style.isBold)
    }

    @Test
    fun `success helper renders green-bold SUCCESS then gray content`() {
        val component = Paint.success("Notepad saved")
        assertEquals("SUCCESS Notepad saved", component.string)

        val (prefix, _, content) = component.siblings
        assertEquals("SUCCESS", prefix.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN), prefix.style.color)
        assertEquals(true, prefix.style.isBold)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), content.style.color)
    }

    @Test
    fun `usage helper renders aqua-bold USAGE then gray content`() {
        // Portal emitted the raw legacy string "§b§lUSAGE §7<content>".
        val component = Paint.usage("/command <arg>")
        assertEquals("USAGE /command <arg>", component.string)

        val (prefix, space, content) = component.siblings
        assertEquals("USAGE", prefix.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.AQUA), prefix.style.color)
        assertEquals(true, prefix.style.isBold)
        assertEquals(" ", space.string)
        assertEquals("/command <arg>", content.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), content.style.color)
    }

    @Test
    fun `info helper renders aqua-bold INFO then gray content`() {
        // Nucleus's INFO_COMPONENT: aqua bold label, gray body.
        val component = Paint.info("Sneaking, teleportation ignored")
        assertEquals("INFO Sneaking, teleportation ignored", component.string)

        val (prefix, space, content) = component.siblings
        assertEquals("INFO", prefix.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.AQUA), prefix.style.color)
        assertEquals(true, prefix.style.isBold)
        assertEquals(" ", space.string)
        assertEquals("Sneaking, teleportation ignored", content.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), content.style.color)
    }

    @Test
    fun `warning helper renders gold-bold WARNING then gray content`() {
        // Nucleus's WARNING_COMPONENT: gold bold label, gray body.
        val component = Paint.warning("This cannot be undone.")
        assertEquals("WARNING This cannot be undone.", component.string)

        val (prefix, space, content) = component.siblings
        assertEquals("WARNING", prefix.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GOLD), prefix.style.color)
        assertEquals(true, prefix.style.isBold)
        assertEquals(" ", space.string)
        assertEquals("This cannot be undone.", content.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), content.style.color)
    }

    // --- Nucleus's wider vocabulary (aqua, gold) and clickable runs ---

    @Test
    fun `aqua and gold are content colors, not just prefixes`() {
        // Nucleus coloured "here" aqua in the admin back-link and gold in the
        // embassy delete confirmation, so both leave the prefix-only cupboard.
        assertEquals("aqua", Paint.aqua("here").style.color?.serialize())
        assertEquals("gold", Paint.gold("here").style.color?.serialize())
    }

    @Test
    fun `runs attaches a run-command click event to the content`() {
        val component = Paint.gold.runs("/embassy delete Home")("here")
        assertEquals("here", component.string)
        assertEquals(ClickEvent.RunCommand("/embassy delete Home"), component.style.clickEvent)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GOLD), component.style.color)
    }

    @Test
    fun `runs composes with the rest of a message and leaves its siblings alone`() {
        val component = Paint("You can click ", Paint.aqua.runs("/tp 1 2 3")("here"), " to go back.")
        assertEquals("You can click here to go back.", component.string)
        assertNull(component.style.clickEvent)
        assertEquals(ClickEvent.RunCommand("/tp 1 2 3"), component.siblings[1].style.clickEvent)
        assertNull(component.siblings[0].style.clickEvent)
    }

    @Test
    fun `helpers accept nested styled components in their content`() {
        val component = Paint.error("Player ", Paint.red("Steve"), " not found or is offline")
        assertEquals("ERROR Player Steve not found or is offline", component.string)

        val content = component.siblings[2]
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), content.style.color)
        assertEquals(
            TextColor.fromLegacyFormat(ChatFormatting.RED),
            content.siblings[1].style.color,
        )
    }
}
