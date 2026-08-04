package eu.mctraveler.tablist

import eu.mctraveler.text.Paint
import java.util.EnumSet
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
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
            // latency and health), keeping the bracketed ping and the hearts current.
            server.playerList.broadcastAll(
                ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                    players,
                ),
            )
        }
    }

    /** One heart is worth two points of health, matching vanilla's own HUD. */
    private const val HEALTH_PER_HEART = 2f

    /** The glyph a heart is drawn with — arbitrary; the tab list has no real heart texture. */
    private const val HEART = "❤"

    /**
     * A tab entry's display name: `<green name> <darkGray [Nms]> <hearts>` (latency:
     * Portal's PlayerInfoBitflagsModule; hearts: new, issue request). Called by
     * ServerPlayerMixin whenever vanilla builds a player-info packet.
     */
    @JvmStatic
    fun tabDisplayName(player: ServerPlayer): Component =
        displayNameWith(player, hearts(player.health, player.maxHealth, player.absorptionAmount))

    /**
     * The tab entry a non-admin viewer is shown for a Spectator/Creative [player] instead
     * of [tabDisplayName]'s real one: full hearts, no Absorption — real health is exactly
     * the kind of tell [SpectatorVisibility] exists to hide, and an
     * admin's Creative/Spectator health carries no real gameplay meaning to a bystander
     * anyway.
     */
    @JvmStatic
    fun displayNameWithFullHearts(player: ServerPlayer): Component =
        displayNameWith(player, hearts(player.maxHealth, player.maxHealth, 0f))

    private fun displayNameWith(player: ServerPlayer, heartsComponent: Component): Component = Paint(
        Paint.green(player.gameProfile.name),
        " ",
        Paint.darkGray("[${player.connection?.latency() ?: 0}ms]"),
        " ",
        heartsComponent,
    )

    /**
     * A heart bar: [health] worth of hearts filled red up to [maxHealth]'s hearts (the rest
     * hollow, dark gray), then [absorption]'s worth again on top in gold — the same
     * red/gold split vanilla's own HUD draws for ordinary health versus Absorption.
     */
    fun hearts(health: Float, maxHealth: Float, absorption: Float): Component {
        val maxHearts = ceil(maxHealth / HEALTH_PER_HEART).toInt().coerceAtLeast(0)
        val filledHearts = (health / HEALTH_PER_HEART).roundToInt().coerceIn(0, maxHearts)
        val emptyHearts = maxHearts - filledHearts
        val goldHearts = (absorption / HEALTH_PER_HEART).roundToInt().coerceAtLeast(0)
        // Paint only drops empty *string* content (Paint.kt's toPart) — an already-built
        // Component, even an empty one, is kept as a sibling — so a zero-count color is
        // left out of the argument list entirely rather than passed as `Paint.color("")`.
        val segments = buildList {
            if (filledHearts > 0) add(Paint.red(HEART.repeat(filledHearts)))
            if (emptyHearts > 0) add(Paint.darkGray(HEART.repeat(emptyHearts)))
            if (goldHearts > 0) add(Paint.gold(HEART.repeat(goldHearts)))
        }
        return Paint(*segments.toTypedArray())
    }

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
