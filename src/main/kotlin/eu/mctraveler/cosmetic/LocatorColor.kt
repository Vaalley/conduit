package eu.mctraveler.cosmetic

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import eu.mctraveler.rank.Rank
import eu.mctraveler.rank.RankFeature
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.util.Optional
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.scores.TeamColor

/**
 * `/locator-color <color>` (issue #84): a Donator's own colour on the locator bar, where
 * other players see them as a dot.
 *
 * It is vanilla's `/waypoint modify <player> color <color>` aimed at yourself, so it uses
 * vanilla's own storage: the colour lives in the player's waypoint icon, which the game
 * writes into their save (`locator_bar_icon`) and carries across death, so it survives
 * respawns, reconnects and restarts with nothing of ours to keep in step.
 *
 * Colours are Minecraft's sixteen names (`gold`, `light_purple`, ...) or hex (`#ff8800`,
 * `ff8800`, `#f80`). `reset` puts the default back. Donators and admins only; the check is in
 * the command body so a malformed line still gets its usage.
 */
object LocatorColor {

    private const val USAGE = "/locator-color <color|#hex|reset>"

    fun register() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> register(dispatcher) }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("locator-color")
                .executes { context -> reply(context) { Paint.usage(USAGE) } }
                .then(
                    Commands.argument("color", StringArgumentType.greedyString())
                        .suggests { _, builder ->
                            SharedSuggestionProvider.suggest(
                                TeamColor.VALUES.map { it.serializedName } + "reset",
                                builder,
                            )
                        }
                        .executes { context ->
                            reply(context) { set(it, StringArgumentType.getString(context, "color")) }
                        },
                ),
        )
    }

    private fun reply(
        context: CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> net.minecraft.network.chat.Component,
    ): Int {
        val player = context.source.playerOrException
        player.sendSystemMessage(handler(player))
        return 1
    }

    /** Whether [player] may change their locator colour: a Donator, or an admin. */
    fun allowed(player: ServerPlayer): Boolean =
        runCatching { RankFeature.rankOf(player) == Rank.DONATOR }.getOrDefault(false) ||
            RegionsFeature.isAdmin(player)

    private fun set(player: ServerPlayer, input: String): net.minecraft.network.chat.Component {
        if (!allowed(player)) return Paint.error("Only Donators can change their locator colour")
        val text = input.trim()
        if (text.equals("reset", ignoreCase = true)) {
            apply(player, null)
            return Paint.success("Your locator colour is back to the default")
        }
        val color = parse(text)
            ?: return Paint.error("Use a Minecraft colour name (like gold) or a hex colour like #ff8800, or reset")
        apply(player, color)
        return Paint.success("Your locator colour is now ", Paint.rgb(color)(label(text, color)))
    }

    /**
     * Sets [player]'s waypoint colour the way `/waypoint modify` does: untrack, change, track, so
     * everyone who can see them is sent the new colour.
     */
    fun apply(player: ServerPlayer, rgb: Int?) {
        val waypoints = player.level().waypointManager
        waypoints.untrackWaypoint(player)
        player.waypointIcon().color = Optional.ofNullable(rgb)
        waypoints.trackWaypoint(player)
    }

    /** A Minecraft colour name, or `#rrggbb` / `rrggbb` / `#rgb` / `rgb`; null when it is neither. */
    fun parse(input: String): Int? {
        val text = input.trim()
        TeamColor.byName(text.lowercase().replace(' ', '_').replace('-', '_'))?.let { return it.rgb() }
        val hex = text.removePrefix("#")
        if (hex.any { it !in "0123456789abcdefABCDEF" }) return null
        return when (hex.length) {
            6 -> hex.toInt(16)
            3 -> hex.map { "$it$it" }.joinToString("").toInt(16)
            else -> null
        }
    }

    private fun label(input: String, rgb: Int): String {
        val name = TeamColor.byName(input.trim().lowercase().replace(' ', '_').replace('-', '_'))
        return name?.serializedName ?: "#%06x".format(rgb)
    }
}
