package eu.mctraveler.gametest

import eu.mctraveler.worlds.DimensionRole
import eu.mctraveler.worlds.WorldsFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.ChatFormatting
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundTabListPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerCommonPacketListenerImpl

/**
 * The unified tab list (spec stories 6-8): the Portal's exact header and footer with the
 * footer's TPS now the server's real TPS (deviation 4), entries carrying latency in the
 * display name, and every player in one list regardless of World.
 *
 * Expected texts are the inventory's literals (portal-feature-inventory.md §2.6/§2.18).
 */
class TabListGameTest {

    @GameTest
    fun headerAndFooterAreSentOnJoin(helper: GameTestHelper) {
        val player = helper.makeMockServerPlayerInLevel()
        val packet = PacketCapture.drainOf<ClientboundTabListPacket>(player).lastOrNull()
            ?: throw AssertionError("no tab list header/footer packet was sent on join")

        assertHeader(packet.header())
        assertFooter(packet.footer())

        removePlayers(helper, player)
        helper.succeed()
    }

    @GameTest(maxTicks = 100)
    fun headerAndFooterRefreshPeriodically(helper: GameTestHelper) {
        val player = helper.makeMockServerPlayerInLevel()
        PacketCapture.drain(player) // discard the join burst

        // A fresh header/footer must arrive on its own within the refresh interval,
        // keeping the footer's TPS live.
        helper.runAfterDelay(30) {
            val packet = PacketCapture.drainOf<ClientboundTabListPacket>(player).lastOrNull()
                ?: throw AssertionError("no tab list refresh arrived within 30 ticks")

            assertHeader(packet.header())
            assertFooter(packet.footer())

            removePlayers(helper, player)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun tabEntriesShowNameWithLatency(helper: GameTestHelper) {
        val viewer = helper.makeMockServerPlayerInLevel()
        PacketCapture.drain(viewer)
        val joiner = helper.makeMockServerPlayerInLevel()

        // The join broadcast the viewer receives already carries `<name> [<N>ms]`.
        assertDisplayName(displayNameSentFor(viewer, joiner), joiner, latencyMs = 0)

        // Once a latency measurement lands, the refresh updates the bracketed number.
        setLatency(joiner, 123)
        PacketCapture.drain(viewer)
        helper.runAfterDelay(30) {
            assertDisplayName(displayNameSentFor(viewer, joiner), joiner, latencyMs = 123)
            removePlayers(helper, viewer, joiner)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun playersInDifferentWorldsShareOneList(helper: GameTestHelper) {
        val server = helper.level.server
        val viewer = helper.makeMockServerPlayerInLevel()
        val traveler = helper.makeMockServerPlayerInLevel()

        // The real Secondary World, not a stand-in: this test predates ticket 04's
        // topology and used the vanilla nether until the Worlds service existed.
        val secondary = checkNotNull(WorldsFeature.worlds) {
            "the Worlds service did not come up with the test server"
        }.all.single { it.id == "secondary" }
        val destination = server.getLevel(secondary.dimension(DimensionRole.OVERWORLD))
            ?: throw AssertionError("Secondary's overworld is not loaded on the test server")
        traveler.teleportTo(destination, 0.5, 128.0, 0.5, emptySet(), 0f, 0f, true)
        check(traveler.level() === destination) { "the traveler did not reach Secondary" }

        PacketCapture.drain(viewer)
        helper.runAfterDelay(30) {
            val packets = PacketCapture.drain(viewer)

            val removed = packets.filterIsInstance<ClientboundPlayerInfoRemovePacket>()
                .flatMap { it.profileIds() }
            check(traveler.uuid !in removed) {
                "the viewer's tab list dropped the player in the other World"
            }

            val refreshed = packets.filterIsInstance<ClientboundPlayerInfoUpdatePacket>()
                .filter { ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME in it.actions() }
                .flatMap { it.entries() }
                .map { it.profileId() }
            check(refreshed.containsAll(listOf(viewer.uuid, traveler.uuid))) {
                "the tab refresh does not cover players in every World: $refreshed"
            }

            removePlayers(helper, viewer, traveler)
            helper.succeed()
        }
    }

    // --- Shared assertions -----------------------------------------------------------------

    /** The Portal's exact header line — the expected runs below are the inventory's literals. */
    private fun assertHeader(header: Component) {
        assertRendered(
            "tab header",
            header,
            listOf(
                "             " to null,
                "MCTraveler" to ChatFormatting.GREEN,
                "             \n" to null,
            ),
        )
    }

    /**
     * The Portal's exact footer plus the TPS line, whose value must be a real one-decimal
     * TPS (deviation 4) rather than any fixed literal.
     */
    private fun assertFooter(footer: Component) {
        val parts = flatten(footer)
        check(parts.size == 5) { "tab footer has parts ${parts.map { it.first }}" }
        assertRendered(
            "tab footer",
            footer,
            listOf(
                "\n" to null,
                "          play.mctraveler.eu          " to ChatFormatting.GRAY,
                "\n" to null,
                "TPS: " to ChatFormatting.DARK_GRAY,
                parts[4].first to ChatFormatting.YELLOW, // value asserted below
            ),
        )
        val tps = parts[4].first
        check(tps.matches(Regex("""\d+\.\d"""))) { "TPS \"$tps\" is not a one-decimal number" }
        check(tps.toDouble() in 0.0..20.0) { "TPS $tps is outside 0..20" }
    }

    /** Asserts a component renders as the given (text, color) runs, resolving style inheritance. */
    private fun assertRendered(
        what: String,
        component: Component,
        expected: List<Pair<String, ChatFormatting?>>,
    ) {
        val actual = flatten(component).map { (text, color) -> text to color }
        val want = expected.map { (text, formatting) ->
            text to formatting?.let(TextColor::fromLegacyFormat)
        }
        check(actual == want) { "$what rendered as $actual, expected $want" }
    }

    private fun flatten(component: Component): List<Pair<String, TextColor?>> =
        component.toFlatList(component.style).map { it.string to it.style.color }

    /** The latency-carrying tab entry display name (inventory §2.18's literals below). */
    private fun assertDisplayName(displayName: Component, player: ServerPlayer, latencyMs: Int) {
        assertRendered(
            "tab display name of ${player.uuid}",
            displayName,
            listOf(
                player.gameProfile.name to ChatFormatting.GREEN,
                " " to null,
                "[${latencyMs}ms]" to ChatFormatting.DARK_GRAY,
            ),
        )
    }

    /** The display name most recently sent to [viewer] for [subject]'s tab entry. */
    private fun displayNameSentFor(viewer: ServerPlayer, subject: ServerPlayer): Component =
        PacketCapture.drainOf<ClientboundPlayerInfoUpdatePacket>(viewer)
            .flatMap { it.entries() }
            .lastOrNull { it.profileId() == subject.uuid && it.displayName() != null }
            ?.displayName()
            ?: throw AssertionError("no display name was sent to the viewer for ${subject.uuid}")

    /** Simulates a keep-alive latency measurement (the field vanilla sets on ping). */
    private fun setLatency(player: ServerPlayer, latencyMs: Int) {
        ServerCommonPacketListenerImpl::class.java.getDeclaredField("latency")
            .apply { isAccessible = true }
            .setInt(player.connection, latencyMs)
    }

    private fun removePlayers(helper: GameTestHelper, vararg players: ServerPlayer) {
        val playerList = helper.level.server.playerList
        players.forEach(playerList::remove)
    }
}
