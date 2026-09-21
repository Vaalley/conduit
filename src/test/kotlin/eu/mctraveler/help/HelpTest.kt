package eu.mctraveler.help

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import eu.mctraveler.command.Audience
import eu.mctraveler.rank.Rank
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure logic behind `/help`, against a stub Brigadier dispatcher. */
class HelpTest {

    private class Viewer(val admin: Boolean)

    private data class Run(
        val text: String,
        val color: String?,
        val bold: Boolean,
        val underlined: Boolean,
        val struck: Boolean,
        val click: String?,
    )

    private fun runs(component: Component): List<Run> =
        component.toFlatList(component.style).map {
            Run(
                it.string,
                it.style.color?.serialize(),
                it.style.isBold,
                it.style.isUnderlined,
                it.style.isStrikethrough,
                (it.style.clickEvent as? ClickEvent.RunCommand)?.command(),
            )
        }

    private fun literal(name: String): LiteralArgumentBuilder<Viewer> = LiteralArgumentBuilder.literal(name)
    private fun word(name: String): RequiredArgumentBuilder<Viewer, String> =
        RequiredArgumentBuilder.argument(name, StringArgumentType.word())

    private val adminOnly: (Viewer) -> Boolean = { it.admin }

    /** help, msg (+tell, w), reply (+r), region and rg trees, admin-only ban/rank, and [filler] more. */
    private fun dispatcher(filler: Int = 0): CommandDispatcher<Viewer> {
        val dispatcher = CommandDispatcher<Viewer>()
        dispatcher.register(literal("help").executes { 1 })
        val msg = dispatcher.register(
            literal("msg").then(word("target").then(word("message").executes { 1 })),
        )
        dispatcher.register(literal("tell").redirect(msg))
        dispatcher.register(literal("w").redirect(msg))
        val reply = dispatcher.register(literal("reply").then(word("message").executes { 1 }))
        dispatcher.register(literal("r").redirect(reply))
        for (alias in listOf("region", "rg")) {
            dispatcher.register(
                literal(alias).executes { 1 }
                    .then(literal("rename").then(word("name").executes { 1 }))
                    .then(literal("add").then(word("player").executes { 1 }))
                    .then(literal("bounds").executes { 1 })
                    .then(literal("locate").then(word("name").executes { 1 })),
            )
        }
        dispatcher.register(literal("ban").requires(adminOnly).then(word("player").executes { 1 }))
        dispatcher.register(literal("rank").requires(adminOnly).executes { 1 })
        dispatcher.register(literal("postcard").executes { 1 }.then(word("caption").executes { 1 }))
        dispatcher.register(literal("Zebra").executes { 1 })
        dispatcher.register(literal("apple").executes { 1 })
        dispatcher.register(literal("Banana").executes { 1 })
        repeat(filler) { dispatcher.register(literal("cmd%02d".format(it)).executes { 1 }) }
        return dispatcher
    }

    private val catalog = HelpCatalog(
        mapOf(
            "help" to CommandHelp("Shows help."),
            "region" to CommandHelp(
                "A region protects land.\nCall an admin for more.",
                aliases = listOf("rg"),
                adminOnlyOptions = setOf("bounds", "locate"),
            ),
        ),
    )

    private fun index(admin: Boolean = false, filler: Int = 0) =
        HelpIndex.build(dispatcher(filler), Viewer(admin), admin, catalog)

    private fun names(index: HelpIndex<Viewer>) = index.entries.map { it.name }

    // -- sorting, collapsing, pagination --

    @Test
    fun `help sorts to the very top and the rest is alphabetical ignoring case`() {
        assertEquals(
            listOf("help", "apple", "Banana", "msg", "postcard", "region", "reply", "Zebra"),
            names(index()),
        )
    }

    @Test
    fun `aliases collapse into their primary entry`() {
        val index = index()
        // A redirect alias is detected without any declaration ...
        assertEquals(listOf("tell", "w"), index.find("msg")!!.aliases)
        assertEquals(listOf("r"), index.find("reply")!!.aliases)
        // ... a separately registered tree is collapsed by the catalog's declaration.
        assertEquals(listOf("rg"), index.find("region")!!.aliases)
        listOf("tell", "w", "r", "rg").forEach { assertFalse(it in names(index), "$it is listed on its own") }
        assertEquals("/region, /rg", index.find("region")!!.namesLine)
    }

    @Test
    fun `a page holds ten entries and the page count follows`() {
        val index = index(filler = 20)
        assertEquals(28, index.entries.size)
        assertEquals(3, index.pageCount)
        assertEquals(10, index.page(1).size)
        assertEquals(10, index.page(2).size)
        assertEquals(8, index.page(3).size)
        assertEquals("help", index.page(1).first().name)
        assertTrue(index.page(0).isEmpty())
        assertTrue(index.page(4).isEmpty())
        assertEquals(1, index().pageCount)
    }

    @Test
    fun `admin-only roots are listed for admins and never for anyone else`() {
        assertFalse("ban" in names(index(admin = false)))
        assertFalse("rank" in names(index(admin = false)))
        assertTrue("ban" in names(index(admin = true)))
        assertTrue("rank" in names(index(admin = true)))
        assertNull(index(admin = false).find("ban"))
        assertNull(index(admin = false).find("/rank"))
    }

    // -- argument resolution --

    @Test
    fun `a command resolves with or without the slash and through an alias`() {
        val index = index()
        val region = index.find("region")
        assertNotNull(region)
        assertEquals(region, index.find("/region"))
        assertEquals(region, index.find("rg"))
        assertEquals(region, index.find("/RG"))
        assertNull(index.find("nope"))
    }

    @Test
    fun `no argument and a page number render the list`() {
        val pages = HelpPages(index(filler = 20))
        val first = pages.render(null)
        assertFalse(first.isError)
        assertEquals(pages.listPage(1).string, first.message.string)
        assertEquals(pages.listPage(1).string, pages.render("1").message.string)
        assertEquals(pages.listPage(2).string, pages.render("2").message.string)
        assertEquals(pages.listPage(3).string, pages.render("3").message.string)
    }

    @Test
    fun `the command page opens for name, slash name and alias`() {
        val pages = HelpPages(index())
        val expected = pages.render("region").message.string
        assertEquals(expected, pages.render("/region").message.string)
        assertEquals(expected, pages.render("rg").message.string)
        assertEquals(expected, pages.render("/rg").message.string)
        assertTrue(expected.contains("/region, /rg"))
    }

    @Test
    fun `an out of range or invalid page is an error naming the valid range`() {
        val pages = HelpPages(index(filler = 20))
        for (bad in listOf("4", "0", "-1", "99999999999")) {
            val reply = pages.render(bad)
            assertTrue(reply.isError, bad)
            assertTrue(reply.message.string.contains("between 1 and 3"), reply.message.string)
        }
        assertTrue(HelpPages(index()).render("2").message.string.contains("only 1 page"))
    }

    @Test
    fun `an unknown command, or one the viewer cannot use, is the same error`() {
        val pages = HelpPages(index(admin = false))
        val unknown = pages.render("nonexistent")
        assertTrue(unknown.isError)
        assertTrue(unknown.message.string.startsWith("ERROR Unknown command /nonexistent"))
        val hidden = pages.render("/ban")
        assertTrue(hidden.isError)
        assertEquals(unknown.message.string.replace("nonexistent", "ban"), hidden.message.string)
    }

    // -- layout --

    @Test
    fun `a list page has the header, the hint, the commands and the footer`() {
        val message = HelpPages(index(filler = 20)).listPage(2)
        val lines = message.string.split('\n')
        assertEquals(10 + 3, lines.size)
        assertEquals("            ( Help menu )            ", lines.first())
        assertEquals("Type /help <command> to find out what a specific command does.", lines[1])
        assertEquals(HelpPages.STRIKE + "( Page 1 2 3 )" + HelpPages.STRIKE, lines.last())

        val all = runs(message)
        assertEquals(listOf("            ", "( Help menu )", "            "), all.take(3).map { it.text })
        assertTrue(all.take(3).all { it.color == "yellow" })
        assertEquals(listOf(true, false, true), all.take(3).map { it.struck })
        val hint = all[4]
        assertEquals("yellow", hint.color)
    }

    @Test
    fun `the footer underlines the current page and makes the others buttons`() {
        val footer = runs(HelpPages(index(filler = 20)).listPage(2)).takeLast(9)
        val numbers = footer.filter { it.text in setOf("1", "2", "3") }
        assertEquals(listOf("1", "2", "3"), numbers.map { it.text })
        val current = numbers.single { it.text == "2" }
        assertTrue(current.bold && current.underlined)
        assertNull(current.click)
        assertEquals("/help 1", numbers.single { it.text == "1" }.click)
        assertEquals("/help 3", numbers.single { it.text == "3" }.click)
        assertTrue(numbers.filter { it.text != "2" }.none { it.bold || it.underlined })
        assertTrue(numbers.all { it.color == "yellow" })
    }

    @Test
    fun `every listed command name is a white button that opens its own page`() {
        val all = runs(HelpPages(index()).listPage(1))
        val region = all.single { it.text == "/region" }
        assertEquals("white", region.color)
        assertEquals("/help region", region.click)
        val help = all.first { it.text == "/help" }
        assertEquals("/help help", help.click)
    }

    @Test
    fun `required and optional parameters are the same gray`() {
        val all = runs(HelpPages(index()).listPage(1))
        fun after(command: String, count: Int): List<Pair<String, String?>> {
            val at = all.indexOfFirst { it.text == command }
            return all.drop(at + 1).filter { it.text != " " }.take(count).map { it.text to it.color }
        }
        assertEquals(listOf("<target>" to "gray", "<message>" to "gray"), after("/msg", 2))
        assertEquals(listOf("[caption]" to "gray"), after("/postcard", 1))
        assertEquals(listOf("[option]" to "gray"), after("/region", 1))
    }

    // -- syntax --

    @Test
    fun `syntax collapses subcommands to option and marks trailing parameters optional`() {
        val index = index(admin = true)
        assertEquals("/msg <target> <message>", index.syntax(index.find("msg")!!))
        assertEquals("/reply <message>", index.syntax(index.find("reply")!!))
        assertEquals("/postcard [caption]", index.syntax(index.find("postcard")!!))
        assertEquals("/region [option]", index.syntax(index.find("region")!!))
        assertEquals("/ban <player>", index.syntax(index.find("ban")!!))
        assertEquals("/apple", index.syntax(index.find("apple")!!))
    }

    @Test
    fun `a root whose only subcommand is admin-only shows no option to everyone else`() {
        val dispatcher = CommandDispatcher<Viewer>()
        dispatcher.register(literal("rtp").executes { 1 }.then(literal("sign").executes { 1 }).then(literal("end").executes { 1 }))
        val info = HelpCatalog(mapOf("rtp" to CommandHelp("Random teleport.", adminOnlyOptions = setOf("sign"))))
        val member = HelpIndex.build(dispatcher, Viewer(false), false, info)
        val admin = HelpIndex.build(dispatcher, Viewer(true), true, info)
        assertEquals("/rtp [end]", member.syntax(member.find("rtp")!!))
        assertEquals("/rtp [option]", admin.syntax(admin.find("rtp")!!))
    }

    @Test
    fun `an argument that is required only in a nested chain stays required`() {
        val dispatcher = CommandDispatcher<Viewer>()
        dispatcher.register(
            literal("gm").then(
                RequiredArgumentBuilder.argument<Viewer, Int>("mode", IntegerArgumentType.integer())
                    .executes { 1 }
                    .then(word("target").executes { 1 }),
            ),
        )
        val index = HelpIndex.build(dispatcher, Viewer(false), false, HelpCatalog.EMPTY)
        assertEquals("/gm <mode> [target]", index.syntax(index.find("gm")!!))
    }

    // -- the command page --

    @Test
    fun `options hide admin-only subcommands from everyone else`() {
        val member = index(admin = false)
        val admin = index(admin = true)
        assertEquals(listOf("rename", "add"), member.options(member.find("region")!!))
        assertEquals(listOf("rename", "add", "bounds", "locate"), admin.options(admin.find("region")!!))
    }

    @Test
    fun `the command page lists the names, the options and the explanation`() {
        val page = HelpPages(index(admin = false)).render("region").message
        assertEquals(
            listOf(
                "            ( Help menu )            ",
                "/region, /rg",
                "Available options are: rename, add",
                "",
                "A region protects land.",
                "Call an admin for more.",
                "",
            ),
            page.string.split('\n'),
        )
        val all = runs(page)
        val colors = all.associate { it.text to it.color }
        assertEquals("white", colors["/region, /rg"])
        assertTrue(all.single { it.text == "/region, /rg" }.bold)
        assertEquals("white", colors["Available options are: rename, add"])
        assertEquals("gray", colors["A region protects land."])
        assertEquals("gray", colors["Call an admin for more."])
    }

    @Test
    fun `a command with no catalog entry falls back to its usage and says it has no description`() {
        val page = HelpPages(index()).render("msg").message.string.split('\n')
        assertEquals("/msg, /tell, /w", page[1])
        assertTrue(page.contains("/msg <target> <message>"), page.toString())
        assertTrue(page[page.size - 2].startsWith("No description"), page.toString())
        assertEquals("", page.last())
    }

    // -- markdown --

    @Test
    fun `the markdown page shows every code in its own style`() {
        val page = HelpPages(index()).render("markdown")
        assertFalse(page.isError)
        val text = page.message.string
        val header = "            ( Help menu )            "
        assertEquals(header, text.split('\n').first())
        val all = runs(page.message)
        for (code in "0123456789abcdef") {
            val format = ChatFormatting.getByCode(code)!!
            val sample = all.singleOrNull { it.text == "%$code ${format.name.lowercase()}" }
            assertNotNull(sample, "%$code missing")
            assertEquals(format.name.lowercase(), sample!!.color, "%$code colour")
        }
        val decorations = page.message.toFlatList(page.message.style).associate { it.string to it.style }
        assertTrue(decorations.getValue("%l bold").isBold)
        assertTrue(decorations.getValue("%n underline").isUnderlined)
        assertTrue(decorations.getValue("%m strikethrough").isStrikethrough)
        assertTrue(decorations.getValue("%o italic").isItalic)
        assertTrue(decorations.getValue("%k obfuscated").isObfuscated)
        assertTrue(decorations.containsKey("%r reset"))
        assertTrue(text.contains("Donators and admins"))
        assertTrue(text.contains("<name>"))
    }

    @Test
    fun `every markdown code the page lists is one the sign parser understands`() {
        val listed = HelpPages.MARKDOWN_COLORS + HelpPages.MARKDOWN_DECORATIONS + HelpPages.MARKDOWN_RESET
        for (code in listed) assertNotNull(ChatFormatting.getByCode(code), "%$code")
        // And the parser accepts every one of them as a code, not a literal percent sign.
        for (code in listed) assertEquals("x", eu.mctraveler.text.Markdown.parse("%${code}x").string)
    }

    // -- suggestions / audience --

    @Test
    fun `suggestible names are only what the viewer can use`() {
        assertFalse("ban" in index(admin = false).names())
        assertTrue("rg" in index(admin = false).names())
        assertTrue("ban" in index(admin = true).names())
    }

    @Test
    fun `audiences admit the right people`() {
        for (rank in Rank.entries) {
            assertTrue(Audience.ALL.permits(admin = false, rank = rank))
            assertFalse(Audience.ADMIN.permits(admin = false, rank = rank))
            assertTrue(Audience.ADMIN.permits(admin = true, rank = rank))
            assertTrue(Audience.TRAVELER.permits(admin = true, rank = rank))
            assertTrue(Audience.DONATOR.permits(admin = true, rank = rank))
        }
        assertFalse(Audience.TRAVELER.permits(admin = false, rank = Rank.NEWBIE))
        assertTrue(Audience.TRAVELER.permits(admin = false, rank = Rank.TRAVELER))
        assertTrue(Audience.TRAVELER.permits(admin = false, rank = Rank.DONATOR))
        assertFalse(Audience.DONATOR.permits(admin = false, rank = Rank.NEWBIE))
        assertFalse(Audience.DONATOR.permits(admin = false, rank = Rank.TRAVELER))
        assertTrue(Audience.DONATOR.permits(admin = false, rank = Rank.DONATOR))
    }

    @Test
    fun `a hidden command, and an alias of it, is not listed, found or suggested for anyone`() {
        val dispatcher = dispatcher()
        val execute = dispatcher.register(literal("execute").executes { 1 })
        dispatcher.register(literal("exec").redirect(execute))
        dispatcher.register(literal("gamemode").executes { 1 })
        val hidden = HelpCatalog(emptyMap(), hidden = setOf("execute"))
        for (admin in listOf(false, true)) {
            val index = HelpIndex.build(dispatcher, Viewer(admin), admin, hidden)
            assertFalse("execute" in names(index) || "exec" in names(index), "listed: ${names(index)}")
            assertNull(index.find("execute"))
            assertNull(index.find("/exec"))
            assertFalse(index.names().any { it == "execute" || it == "exec" }, "suggested: ${index.names()}")
            assertTrue("gamemode" in names(index))
        }
        assertTrue("execute" in names(HelpIndex.build(dispatcher, Viewer(true), true, catalog)))
    }

    @Test
    fun `the default catalog hides the listed vanilla commands and describes the ones it keeps`() {
        val hidden = HelpCatalog.DEFAULT.hidden
        val requested = listOf(
            "advancements", "attribute", "bossbar", "clear", "damage", "datapack", "defaultgamemode", "enchant",
            "execute", "jfr", "loot", "particle", "place", "playsound", "posteffect", "random", "ride", "rotate",
            "scoreboard", "seed", "setblock", "spreadplayers", "stopsound", "stopwatch", "summon", "swing",
            "tellraw", "tick", "title", "transfer", "trigger", "worldborder",
        )
        for (name in requested) assertTrue(name in hidden, "/$name is not hidden")
        for (name in listOf("gamemode", "give", "list", "me", "time", "teleport", "kill")) {
            val info = HelpCatalog.DEFAULT.of(name)
            assertNotNull(info, "/$name has no description")
            assertTrue(info!!.description.isNotBlank())
        }
        assertTrue(hidden.none { it in VanillaHelp.ENTRIES }, "a hidden command also has a description")
    }

    @Test
    fun `the default catalog describes a redirect-free set of primary names and declares rg`() {
        assertEquals("region", HelpCatalog.DEFAULT.declaredAliases["rg"])
        assertTrue(HelpCatalog.DEFAULT.of("region")!!.description.contains("protects a piece of land"))
    }

}
