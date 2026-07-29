package eu.mctraveler.tablist

import eu.mctraveler.text.Paint
import java.util.EnumSet
import java.util.Locale
import kotlin.math.min
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundTabListPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

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

    fun register() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            handler.send(headerFooterPacket(server))
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (server.tickCount % REFRESH_INTERVAL_TICKS != 0) return@register
            val players = server.playerList.players
            if (players.isEmpty()) return@register
            server.playerList.broadcastAll(headerFooterPacket(server))
            // Re-sends every display name (built by ServerPlayerMixin from the live
            // latency), keeping the bracketed ping current as measurements land.
            server.playerList.broadcastAll(
                ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                    players,
                ),
            )
        }
    }

    /**
     * A tab entry's display name: `<green name> <darkGray [Nms]>` (Portal:
     * PlayerInfoBitflagsModule's latency display names). Called by ServerPlayerMixin
     * whenever vanilla builds a player-info packet.
     */
    @JvmStatic
    fun tabDisplayName(player: ServerPlayer): Component = Paint(
        Paint.green(player.gameProfile.name),
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
