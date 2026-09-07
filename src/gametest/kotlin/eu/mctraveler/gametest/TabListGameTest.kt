package eu.mctraveler.gametest

import eu.mctraveler.tablist.SpectatorVisibility
import eu.mctraveler.tablist.TabListFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.ChatFormatting
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.network.protocol.game.ClientboundTabListPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerCommonPacketListenerImpl
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.criteria.ObjectiveCriteria

/**
 * The unified tab list (spec stories 6-8): the Portal's exact header and footer with the
 * footer's TPS now the server's real TPS (deviation 4), entries carrying latency in the
 * display name, and every player in one list wherever they are standing. Hearts are a real
 * vanilla scoreboard objective ([TabListFeature.HEALTH_OBJECTIVE]) now, not drawn glyphs, so
 * this suite asserts the objective's shape and the score vanilla keeps in sync — never a
 * heart-bar Component, since none is built here any more.
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

    @GameTest(maxTicks = 100)
    fun anAwayPlayerCarriesTheAwayPrefix(helper: GameTestHelper) {
        val viewer = helper.makeMockServerPlayerInLevel()
        val joiner = helper.makeMockServerPlayerInLevel()
        eu.mctraveler.rank.RankFeature.setRank(joiner, eu.mctraveler.rank.Rank.TRAVELER)
        helper.level.server.commands.performPrefixedCommand(joiner.createCommandSourceStack(), "away")
        PacketCapture.drain(viewer)

        helper.runAfterDelay(30) {
            assertRendered(
                "away tab display name of ${joiner.uuid}",
                displayNameSentFor(viewer, joiner),
                listOf(
                    "[Away] " to ChatFormatting.GRAY,
                    joiner.gameProfile.name to ChatFormatting.GREEN,
                    " " to null,
                    "[0ms]" to ChatFormatting.DARK_GRAY,
                ),
            )
            removePlayers(helper, viewer, joiner)
            helper.succeed()
        }
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

        subject.setGameMode(GameType.SPECTATOR)

        val bystanderEntry = lastEntrySentFor(bystander, subject)
        helper.assertValueEqual(
            bystanderEntry.gameMode(),
            GameType.SURVIVAL,
            "the gamemode a non-admin bystander is shown for a spectating admin",
        )

        val staffEntry = lastEntrySentFor(staff, subject)
        helper.assertValueEqual(
            staffEntry.gameMode(),
            GameType.SPECTATOR,
            "the gamemode a fellow admin is shown for a spectating admin",
        )

        val selfEntry = lastEntrySentFor(subject, subject)
        helper.assertValueEqual(
            selfEntry.gameMode(),
            GameType.SPECTATOR,
            "the gamemode the spectating admin's own client is shown",
        )

        removePlayers(helper, subject, bystander, staff)
        helper.succeed()
    }

    /**
     * The tab list's real vanilla heart bar ([TabListFeature.HEALTH_OBJECTIVE]) has the same
     * tell [SpectatorVisibility] already exists to remove: a bystander must not see a
     * Spectator or Creative admin's real (possibly mid-combat) health, even though vanilla
     * broadcasts that score identically to everyone.
     *
     * Nothing here can drive that real broadcast: the sync from health to score is
     * `ServerPlayer.doTick()`'s own, called off the *connection's* own tick — which a
     * gametest mock connection, wired up directly rather than accepted through the network,
     * never receives (confirmed: `tickCount` advances but `Stats.PLAY_TIME`, also only
     * awarded from `doTick()`, never does) — and the score cannot be set by hand either,
     * since `HEALTH` is one of vanilla's own read-only criteria (`Scoreboard
     * .getOrCreatePlayerScore(...).set(...)` throws exactly as `/scoreboard players set`
     * would). So this builds the packet vanilla's sync *would* send by hand, and asserts
     * purely on [SpectatorVisibility.maskScore] — the one seam this mod owns.
     */
    @GameTest
    fun spectatorAndCreativeHealthScoreIsMaskedFromNonAdmins(helper: GameTestHelper) {
        val subject = MessageCapturingPlayer.join(helper, "T20Health")
        subject.makeAdmin()
        val bystander = MessageCapturingPlayer.join(helper, "T20HealthBystander")
        val staff = MessageCapturingPlayer.join(helper, "T20HealthStaff")
        staff.makeAdmin()

        subject.setGameMode(GameType.CREATIVE)
        // Half of a 20-max bar, so real vs. masked are distinguishable.
        val realPacket = ClientboundSetScorePacket(
            subject.gameProfile.name,
            TabListFeature.HEALTH_OBJECTIVE,
            10,
            java.util.Optional.empty(),
            java.util.Optional.empty(),
        )

        val bystanderView = SpectatorVisibility.maskScore(bystander, realPacket)
        helper.assertValueEqual(
            checkNotNull(bystanderView) { "a non-admin bystander's score for a Creative admin was not masked" }.score(),
            subject.maxHealth.toInt(),
            "a non-admin bystander's score for a Creative admin",
        )
        helper.assertTrue(
            SpectatorVisibility.maskScore(staff, realPacket) == null,
            "a fellow admin's score for a Creative admin was masked",
        )
        helper.assertTrue(
            SpectatorVisibility.maskScore(subject, realPacket) == null,
            "the Creative admin's own score was masked",
        )
        removePlayers(helper, subject, bystander, staff)
        helper.succeed()
    }

    @GameTest
    fun theHealthObjectiveIsShownInTheListSlotWithHearts(helper: GameTestHelper) {
        val scoreboard = helper.level.server.scoreboard
        val objective = checkNotNull(scoreboard.getObjective(TabListFeature.HEALTH_OBJECTIVE)) {
            "the health objective was never created"
        }
        helper.assertValueEqual(objective.criteria, ObjectiveCriteria.HEALTH, "the health objective's criteria")
        helper.assertValueEqual(
            objective.renderType,
            ObjectiveCriteria.RenderType.HEARTS,
            "the health objective's render type",
        )
        helper.assertValueEqual(
            checkNotNull(scoreboard.getDisplayObjective(DisplaySlot.LIST)) { "no objective is shown in the list slot" },
            objective,
            "the objective shown in the tab-list slot",
        )
        helper.succeed()
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
     * The latency-carrying tab entry display name: `<colored name> [<N>ms]`, one space
     * apart (inventory §2.18's literals below). Hearts are no longer part of this text at
     * all — [TabListFeature.HEALTH_OBJECTIVE] draws those.
     */
    private fun assertDisplayName(
        displayName: Component,
        player: ServerPlayer,
        latencyMs: Int,
        nameColor: ChatFormatting = ChatFormatting.GREEN,
    ) {
        assertRendered(
            "tab display name of ${player.uuid}",
            displayName,
            listOf(
                player.gameProfile.name to nameColor,
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
