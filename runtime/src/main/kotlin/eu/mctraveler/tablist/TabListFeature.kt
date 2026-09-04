package eu.mctraveler.tablist

import eu.mctraveler.Conduit
import eu.mctraveler.reloadable
import eu.mctraveler.text.Paint
import java.util.EnumSet
import java.util.Locale
import kotlin.math.min
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.BlankFormat
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundTabListPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.criteria.ObjectiveCriteria

/**
 * The unified tab list (Portal: TabListFeature + TabListModule + PlayerInfoBitflagsModule).
 *
 * One list for every player regardless of World is the single server's vanilla default;
 * this module contributes the Portal's presentation: the exact header and footer (with the
 * footer's TPS now the server's real TPS — deviation 4).
 */
object TabListFeature {

    /** Once a second, matching the Portal's 1 s TPS sampling cadence. */
    private const val REFRESH_INTERVAL_TICKS = 20

    /**
     * The real server [net.minecraft.world.scores.Scoreboard] objective that paints hearts in
     * the tab list — same the standard vanilla `/scoreboard objectives add <name> health` +
     * `setdisplay list <name>` incantation, not a hand-drawn glyph bar: the "health" criteria
     * is one of vanilla's own auto-tracked scores (`ServerPlayer.doTick()` keeps it in sync
     * every tick with no help from this mod), and `RenderType.HEARTS` in the `list` slot is exactly
     * the real, textured, ten-heart row this feature request was asking for. A name of our own
     * (rather than the bare `"health"` a server admin might reach for by hand) avoids fighting
     * over an objective an admin created for something else.
     */
    const val HEALTH_OBJECTIVE = "mctraveler_health"

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.reloadable.register(::ensureHealthObjective)
        // The objective is server (world-save) state, so it survives a hot reload on its own;
        // only the display slot needs reasserting in case something else changed it meanwhile.
        Conduit.onHotActivate(::ensureHealthObjective)

        ServerPlayConnectionEvents.JOIN.reloadable.register { handler, _, server ->
            handler.send(headerFooterPacket(server))
        }
        // Players online before a hot reload need the header/footer resent.
        Conduit.onHotActivate { server ->
            server.playerList.players.forEach { it.connection.send(headerFooterPacket(server)) }
        }
        ServerTickEvents.END_SERVER_TICK.reloadable.register { server ->
            if (server.tickCount % REFRESH_INTERVAL_TICKS != 0) return@register
            val players = server.playerList.players
            if (players.isEmpty()) return@register
            server.playerList.broadcastAll(headerFooterPacket(server))
            // Re-sends every display name (built by ServerPlayerMixin from the live rank
            // color, name padding and latency), keeping the bracketed ping current. Hearts
            // are the health objective's own concern now — vanilla keeps those in sync.
            server.playerList.broadcastAll(
                ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                    players,
                ),
            )
        }
    }

    /** Creates [HEALTH_OBJECTIVE] if it is not already on [server]'s scoreboard, and shows it. */
    private fun ensureHealthObjective(server: MinecraftServer) {
        val scoreboard = server.scoreboard
        val objective: Objective = scoreboard.getObjective(HEALTH_OBJECTIVE) ?: scoreboard.addObjective(
            HEALTH_OBJECTIVE,
            ObjectiveCriteria.HEALTH,
            Paint.red("❤"),
            ObjectiveCriteria.RenderType.HEARTS,
            false,
            BlankFormat.INSTANCE,
        )
        scoreboard.setDisplayObjective(DisplaySlot.LIST, objective)
    }

    /**
     * A tab entry's display name: `<rank-colored name> <darkGray [Nms]>` (latency: Portal's
     * PlayerInfoBitflagsModule). Hearts are [HEALTH_OBJECTIVE], drawn by the client
     * immediately after this text, not part of it. Called by ServerPlayerMixin whenever
     * vanilla builds a player-info packet.
     */
    @JvmStatic
    fun tabDisplayName(player: ServerPlayer): Component = Paint(
        eu.mctraveler.rank.RankFeature.nameColor(player)(player.gameProfile.name),
        " ",
        Paint.darkGray("[${player.connection?.latency() ?: 0}ms]"),
    )

    /** Header: `             <green MCTraveler>             \n` (13 spaces each side). */
    fun header(): Component =
        Paint("             ", Paint.green("MCTraveler"), "             \n")

    /**
     * Footer: `\n<gray "          play.mctraveler.eu          ">\n<darkGray "TPS: "><yellow tps>`
     * (10 spaces around the address), the [tps] rendered to one decimal.
     */
    fun footer(tps: Double): Component = Paint(
        "\n",
        Paint.gray("          play.mctraveler.eu          "),
        "\n",
        Paint.darkGray("TPS: "),
        Paint.yellow(String.format(Locale.ROOT, "%.1f", tps)),
    )

    /**
     * The server's real TPS (deviation 4 — the Portal showed its event loop's fake ~20)
     * from its average tick time: `min(20, 1000 / mspt)`. No samples yet reads as 20.
     */
    fun tps(averageTickTimeNanos: Long): Double =
        if (averageTickTimeNanos <= 0) 20.0
        else min(20.0, 1_000_000_000.0 / averageTickTimeNanos)

    private fun headerFooterPacket(server: MinecraftServer): ClientboundTabListPacket =
        ClientboundTabListPacket(header(), footer(tps(server.averageTickTimeNanos)))
}
