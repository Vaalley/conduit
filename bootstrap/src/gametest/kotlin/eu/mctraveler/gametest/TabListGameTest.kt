package eu.mctraveler.gametest

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
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level

/**
 * The unified tab list (spec stories 6-8): the Portal's exact header and footer with the
 * footer's TPS now the server's real TPS (deviation 4), entries carrying latency in the
 * display name, and every player in one list wherever they are standing.
 *
 * Expected texts are the inventory's literals (portal-feature-inventory.md §2.6/§2.18).
 */
/** The glyph [TabListFeature.hearts] draws with — kept independent so this file never
 *  reaches into that `private` constant. */
private const val HEART = "❤"

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

        // The join broadcast the viewer receives already carries `<name> [<N>ms]` —
        // still Newbie-colored (ranks feature): the account is brand new, and the
        // promotion below only takes effect on the tab list's next refresh.
        assertDisplayName(
            displayNameSentFor(viewer, joiner),
            joiner,
            latencyMs = 0,
            nameColor = ChatFormatting.DARK_AQUA,
        )
        eu.mctraveler.rank.RankFeature.setRank(joiner, eu.mctraveler.rank.Rank.TRAVELER)

        // Once a latency measurement lands, the refresh updates the bracketed number
        // (and, by now, the rank color too).
        setLatency(joiner, 123)
        PacketCapture.drain(viewer)
        helper.runAfterDelay(30) {
            assertDisplayName(displayNameSentFor(viewer, joiner), joiner, latencyMs = 123)
            removePlayers(helper, viewer, joiner)
            helper.succeed()
        }
    }

    /**
     * Issue request: the ping/hearts should line up in a column instead of drifting with
     * name length, so a short and a long name must right-pad to the same total width
     * before the `[Nms]` bracket — whatever that width is, since it depends on every
     * player online (including ones this test does not control, on the shared gametest
     * server), not a value this test can predict.
     */
    @GameTest
    fun namesArePaddedToLineUpTheColumn(helper: GameTestHelper) {
        val short = MessageCapturingPlayer.join(helper, "T22Short")
        val long = MessageCapturingPlayer.join(helper, "T22MuchLongerName")

        val shortWidth = widthBeforeLatencyBracket(eu.mctraveler.tablist.TabListFeature.tabDisplayName(short))
        val longWidth = widthBeforeLatencyBracket(eu.mctraveler.tablist.TabListFeature.tabDisplayName(long))
        helper.assertValueEqual(shortWidth, longWidth, "the padded column width before [Nms]")

        short.leave()
        long.leave()
        helper.succeed()
    }

    /** The combined character length of every run before the `[Nms]` bracket run. */
    private fun widthBeforeLatencyBracket(displayName: Component): Int {
        val runs = flatten(displayName)
        val bracketIndex = runs.indexOfFirst { (text, _) -> text.startsWith("[") && text.endsWith("ms]") }
        check(bracketIndex >= 0) { "no [Nms] bracket found in $runs" }
        return runs.take(bracketIndex).sumOf { (text, _) -> text.length }
    }

    /**
     * One list for everybody, wherever they are standing.
     *
     * This case used to send the traveler into Secondary's overworld, because
     * the strongest thing it could say was that a whole other World did not
     * split the list. There is one World now, so it says the same thing about
     * the strongest separation left — a different dimension — which is what it
     * asserted before the Worlds existed at all.
     */
    @GameTest(maxTicks = 100)
    fun playersInDifferentDimensionsShareOneList(helper: GameTestHelper) {
        val server = helper.level.server
        val viewer = helper.makeMockServerPlayerInLevel()
        val traveler = helper.makeMockServerPlayerInLevel()

        val destination = server.getLevel(Level.NETHER)
            ?: throw AssertionError("the nether is not loaded on the test server")
        traveler.teleportTo(destination, 0.5, 128.0, 0.5, emptySet(), 0f, 0f, true)
        check(traveler.level() === destination) { "the traveler did not reach the nether" }

        PacketCapture.drain(viewer)
        helper.runAfterDelay(30) {
            val packets = PacketCapture.drain(viewer)

            val removed = packets.filterIsInstance<ClientboundPlayerInfoRemovePacket>()
                .flatMap { it.profileIds() }
            check(traveler.uuid !in removed) {
                "the viewer's tab list dropped the player in the other dimension"
            }

            val refreshed = packets.filterIsInstance<ClientboundPlayerInfoUpdatePacket>()
                .filter { ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME in it.actions() }
                .flatMap { it.entries() }
                .map { it.profileId() }
            check(refreshed.containsAll(listOf(viewer.uuid, traveler.uuid))) {
                "the tab refresh does not cover players in every dimension: $refreshed"
            }

            removePlayers(helper, viewer, traveler)
            helper.succeed()
        }
    }

    /**
     * Issue #20: a Spectator's tab-list name is italicised/greyed for every viewer by
     * vanilla client code that reacts purely to the `GameType` on the tab entry — letting
     * a cheater notice exactly when an admin is spectating them. Only admins may reach
     * Spectator at all (`/gamemode` needs operator permission), so masking it is purely
     * about who else is allowed to see that an admin is doing it — never the spectating
     * admin's own client, which needs the real value to keep noclip and the free camera.
     */
    @GameTest(maxTicks = 100)
    fun spectatorIsHiddenFromNonAdminsButNotFromAdminsOrThemselves(helper: GameTestHelper) {
        val subject = MessageCapturingPlayer.join(helper, "T20Subject")
        subject.makeAdmin()
        val bystander = MessageCapturingPlayer.join(helper, "T20Bystander")
        val staff = MessageCapturingPlayer.join(helper, "T20Staff")
        staff.makeAdmin()
        listOf(subject, bystander, staff).forEach(PacketCapture::drain) // discard join bursts

        // 5/10 hearts, so a masked (full) and a real (damaged) bar are distinguishable.
        subject.health = 10.0f
        subject.setGameMode(GameType.SPECTATOR)

        val bystanderEntry = lastEntrySentFor(bystander, subject)
        helper.assertValueEqual(
            bystanderEntry.gameMode(),
            GameType.SURVIVAL,
            "the gamemode a non-admin bystander is shown for a spectating admin",
        )
        assertHearts(checkNotNull(bystanderEntry.displayName()), redCount = 10, grayCount = 0, goldCount = 0)

        val staffEntry = lastEntrySentFor(staff, subject)
        helper.assertValueEqual(
            staffEntry.gameMode(),
            GameType.SPECTATOR,
            "the gamemode a fellow admin is shown for a spectating admin",
        )
        assertHearts(checkNotNull(staffEntry.displayName()), redCount = 5, grayCount = 5, goldCount = 0)

        val selfEntry = lastEntrySentFor(subject, subject)
        helper.assertValueEqual(
            selfEntry.gameMode(),
            GameType.SPECTATOR,
            "the gamemode the spectating admin's own client is shown",
        )
        assertHearts(checkNotNull(selfEntry.displayName()), redCount = 5, grayCount = 5, goldCount = 0)

        removePlayers(helper, subject, bystander, staff)
        helper.succeed()
    }

    /**
     * Creative has no gamemode tell to hide (only Spectator's tab-list italics do), but a
     * bystander must still not see a Creative admin's real (possibly mid-combat) health.
     */
    @GameTest(maxTicks = 100)
    fun creativeHidesRealHeartsFromNonAdminsButNotTheGameMode(helper: GameTestHelper) {
        val subject = MessageCapturingPlayer.join(helper, "T20Creative")
        subject.makeAdmin()
        val bystander = MessageCapturingPlayer.join(helper, "T20CreBystander")
        listOf(subject, bystander).forEach(PacketCapture::drain)

        subject.health = 10.0f
        subject.setGameMode(GameType.CREATIVE)

        val bystanderEntry = lastEntrySentFor(bystander, subject)
        helper.assertValueEqual(
            bystanderEntry.gameMode(),
            GameType.CREATIVE,
            "the gamemode a non-admin bystander is shown for a Creative admin",
        )
        assertHearts(checkNotNull(bystanderEntry.displayName()), redCount = 10, grayCount = 0, goldCount = 0)

        removePlayers(helper, subject, bystander)
        helper.succeed()
    }

    /** The "fun" case the hearts exist for: an ordinary player's real health, for everybody. */
    @GameTest(maxTicks = 100)
    fun heartsReflectRealHealthForOrdinaryPlayers(helper: GameTestHelper) {
        val viewer = MessageCapturingPlayer.join(helper, "T25Viewer")
        val subject = MessageCapturingPlayer.join(helper, "T25Hurt")
        PacketCapture.drain(viewer)

        subject.health = 7.0f // 3.5 hearts, rounds up to 4
        helper.runAfterDelay(30) {
            assertHearts(displayNameSentFor(viewer, subject), redCount = 4, grayCount = 6, goldCount = 0)
            removePlayers(helper, viewer, subject)
            helper.succeed()
        }
    }

    /** The last tab entry sent to [viewer] for [subject], draining the packet queue once. */
    private fun lastEntrySentFor(
        viewer: ServerPlayer,
        subject: ServerPlayer,
    ): ClientboundPlayerInfoUpdatePacket.Entry =
        PacketCapture.drainOf<ClientboundPlayerInfoUpdatePacket>(viewer)
            .flatMap { it.entries() }
            .lastOrNull { it.profileId() == subject.uuid }
            ?: throw AssertionError("no tab entry was sent to the viewer for ${subject.uuid}")

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

    /**
     * The latency-carrying tab entry display name (inventory §2.18's literals below).
     * Checked by prefix (the colored name) and by tail (`[Nms] <hearts>`) rather than as
     * one exact sequence: a name-alignment padding run may sit between them, and its
     * width depends on every player currently online — including ones this test knows
     * nothing about, on the shared gametest server.
     */
    private fun assertDisplayName(
        displayName: Component,
        player: ServerPlayer,
        latencyMs: Int,
        nameColor: ChatFormatting = ChatFormatting.GREEN,
    ) {
        val actual = flatten(displayName)
        val wantName = player.gameProfile.name to TextColor.fromLegacyFormat(nameColor)
        check(actual.firstOrNull() == wantName) {
            "tab display name of ${player.uuid} does not start with the colored name: $actual"
        }
        val wantTail = listOf(
            "[${latencyMs}ms]" to TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY),
            " " to null,
            HEART.repeat(10) to TextColor.fromLegacyFormat(ChatFormatting.RED),
        )
        check(actual.takeLast(wantTail.size) == wantTail) {
            "tab display name of ${player.uuid} rendered as $actual, wanted it to end with $wantTail"
        }
    }

    /**
     * Asserts the hearts segment at the tail of [displayName]: [redCount] filled (red),
     * [grayCount] hollow (dark gray), then [goldCount] Absorption (gold) — a zero-count
     * color's run is absent, since Paint drops empty segments.
     */
    private fun assertHearts(displayName: Component, redCount: Int, grayCount: Int, goldCount: Int) {
        val expected = listOf(
            HEART.repeat(redCount) to ChatFormatting.RED,
            HEART.repeat(grayCount) to ChatFormatting.DARK_GRAY,
            HEART.repeat(goldCount) to ChatFormatting.GOLD,
        ).filter { (text, _) -> text.isNotEmpty() }
        val actual = flatten(displayName).takeLast(expected.size).map { (text, color) -> text to color }
        val want = expected.map { (text, formatting) -> text to TextColor.fromLegacyFormat(formatting) }
        check(actual == want) { "hearts rendered as $actual, expected $want" }
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
