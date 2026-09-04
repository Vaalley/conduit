package eu.mctraveler.rank

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import eu.mctraveler.MCTraveler
import eu.mctraveler.reloadable
import eu.mctraveler.text.Markdown
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * `/deathmessage <message>` — the Donator perk: a custom line that replaces
 * this player's own death broadcast (`CustomDeathMessageMixin`, over
 * `CombatTracker.getDeathMessage`), markdown permitted.
 */
object DeathMessageFeature {

    private const val MAX_LENGTH = 256

    fun register() {
        CommandRegistrationCallback.EVENT.reloadable.register { dispatcher, _, _ -> register(dispatcher) }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("deathmessage")
                .executes { ctx -> reply(ctx) { Paint.usage("/deathmessage <message>") } }
                .then(
                    Commands.argument("message", StringArgumentType.greedyString())
                        .executes { ctx -> reply(ctx) { set(it, StringArgumentType.getString(ctx, "message")) } },
                ),
        )
    }

    /**
     * [player]'s custom death message, styled, or null if none applies — no
     * message was ever set, or [player] is no longer a Donator (a demotion
     * silently retires the perk rather than leaving a stale message live).
     */
    fun customMessageFor(player: ServerPlayer): Component? {
        if (RankFeature.rankOf(player) != Rank.DONATOR) return null
        return checkNotNull(MCTraveler.persistence) { "Death messages need the Persistence service" }
            .players.deathMessage(player.uuid)
            ?.let(Markdown::parse)
    }

    private fun set(player: ServerPlayer, message: String): Component {
        RankFeature.rankOf(player).let {
            if (it != Rank.DONATOR) return Paint.error("Only Donators can set a custom death message")
        }
        if (message.length > MAX_LENGTH) {
            return Paint.error("Death message must be at most $MAX_LENGTH characters")
        }
        checkNotNull(MCTraveler.persistence).players.setDeathMessage(player.uuid, message)
        return Paint.success("Your death message is now: ", Markdown.parse(message))
    }

    private inline fun reply(
        ctx: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> Component?,
    ): Int {
        val player = ctx.source.playerOrException
        handler(player)?.let(player::sendSystemMessage)
        return Command.SINGLE_SUCCESS
    }
}
