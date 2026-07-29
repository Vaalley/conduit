package eu.mctraveler.motd

import java.util.Optional
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.status.ServerStatus
import net.minecraft.server.players.NameAndId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tier (fabric-loader-junit) for the server-list presence (Portal
 * features/MotdFeature.ts + the main.ts status response). [Motd.decorate] is the pure
 * seam: it takes the status vanilla built and the online-player roster and returns the
 * status actually advertised. Exact strings come from the Portal feature inventory.
 */
class MotdTest {

    private fun vanillaStatus(
        max: Int = 20,
        online: Int = 0,
        version: ServerStatus.Version = ServerStatus.Version("test", 0),
        favicon: ServerStatus.Favicon? = null,
        enforcesSecureChat: Boolean = true,
    ): ServerStatus =
        ServerStatus(
            Component.literal("A Minecraft Server"),
            Optional.of(ServerStatus.Players(max, online, listOf())),
            Optional.of(version),
            Optional.ofNullable(favicon),
            enforcesSecureChat,
        )

    // --- The two MOTD lines (Portal: MotdFeature.test.ts "MOTD lines and formatting") ---

    @Test
    fun `description carries the Portal's exact two MOTD lines`() {
        val description = Motd.decorate(vanillaStatus(), emptyList()).description()
        assertEquals(
            "                  play.MCTraveler.eu\n" +
                "       Celebrating 13 years of vanilla survival",
            description.string,
        )
    }

    @Test
    fun `line one is green with MCTraveler bold and line two is gray`() {
        val description = Motd.decorate(vanillaStatus(), emptyList()).description()
        // Resolve each part's rendered style the way the client does (style inheritance).
        val rendered = description.toFlatList(description.style)
            .map { Triple(it.string, it.style.color, it.style.isBold) }
        val green = TextColor.fromLegacyFormat(ChatFormatting.GREEN)
        val gray = TextColor.fromLegacyFormat(ChatFormatting.GRAY)
        assertEquals(
            listOf(
                Triple("                  play.", green, false),
                Triple("MCTraveler", green, true),
                Triple(".eu", green, false),
                Triple("\n", null, false),
                Triple("       Celebrating 13 years of vanilla survival", gray, false),
            ),
            rendered,
        )
    }

    // --- Live count and sample (Portal main.ts: online count, sample of first 12) ---

    @Test
    fun `player count and max pass through from the vanilla status`() {
        val players = Motd.decorate(vanillaStatus(max = 137, online = 42), emptyList())
            .players().orElseThrow()
        assertEquals(137, players.max())
        assertEquals(42, players.online())
    }

    @Test
    fun `sample lists the first twelve of the roster by name and uuid`() {
        val roster = (1..15).map { NameAndId(UUID(0L, it.toLong()), "player$it") }
        val sample = Motd.decorate(vanillaStatus(online = 15), roster)
            .players().orElseThrow().sample()
        assertEquals(12, sample.size)
        assertEquals("player1", sample.first().name())
        assertEquals(UUID(0L, 1L), sample.first().id())
        assertEquals("player12", sample.last().name())
        assertEquals(UUID(0L, 12L), sample.last().id())
    }

    @Test
    fun `a roster smaller than twelve is sampled whole`() {
        val roster = listOf(
            NameAndId(UUID(0L, 1L), "iElmo"),
            NameAndId(UUID(0L, 2L), "travelcraft2012"),
        )
        val sample = Motd.decorate(vanillaStatus(online = 2), roster)
            .players().orElseThrow().sample()
        assertEquals(roster, sample)
    }

    // --- Everything else is vanilla's own (real max-players, standard favicon,
    // honest enforcesSecureChat, real version — no Portal fakery) ---

    @Test
    fun `version and favicon pass through from the vanilla status`() {
        val version = ServerStatus.Version("MCTraveler test", 999)
        val favicon = ServerStatus.Favicon(byteArrayOf(1, 2, 3))
        val decorated = Motd.decorate(vanillaStatus(version = version, favicon = favicon), emptyList())
        assertSame(version, decorated.version().orElseThrow())
        assertSame(favicon, decorated.favicon().orElseThrow())
    }

    @Test
    fun `secure-chat advertisement passes through honestly either way`() {
        assertTrue(Motd.decorate(vanillaStatus(enforcesSecureChat = true), emptyList()).enforcesSecureChat())
        assertFalse(Motd.decorate(vanillaStatus(enforcesSecureChat = false), emptyList()).enforcesSecureChat())
    }
}
