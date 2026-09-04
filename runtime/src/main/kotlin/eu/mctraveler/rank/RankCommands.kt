package eu.mctraveler.rank

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * `/rank set <player> <rank>` — admin-only, and works on an offline player by
 * name (the same known-username resolution `/rg add`/`/rg remove` use), since
 * granting Donator is exactly the kind of thing an admin does for someone who
 * isn't online right now.
 */
object RankCommands {

    private val onlinePlayerNames = SuggestionProvider<CommandSourceStack> { context, builder ->
        SharedSuggestionProvider.suggest(context.source.onlinePlayerNames, builder)
    }

    private val rankNames = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(Rank.COMMAND_NAMES, builder)
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("rank")
                .executes { ctx -> reply(ctx) { Paint.usage("/rank set <player> <rank>") } }
                .then(
                    Commands.literal("set")
                        .executes { ctx -> reply(ctx) { Paint.usage("/rank set <player> <rank>") } }
                        .then(
                            Commands.argument("player", StringArgumentType.word())
                                .suggests(onlinePlayerNames)
                                .executes { ctx -> reply(ctx) { Paint.usage("/rank set <player> <rank>") } }
                                .then(
                                    Commands.argument("rank", StringArgumentType.word())
                                        .suggests(rankNames)
                                        .executes { ctx ->
                                            reply(ctx) {
                                                setRank(
                                                    it,
                                                    StringArgumentType.getString(ctx, "player"),
                                                    StringArgumentType.getString(ctx, "rank"),
                                                )
                                            }
                                        },
                                ),
                        ),
                ),
        )
    }

    private fun setRank(sender: ServerPlayer, targetName: String, rankName: String): Component {
        RegionsFeature.adminGate(sender)?.let { return it }
        val rank = Rank.parse(rankName)
            ?: return Paint.error("Unknown rank ", Paint.red(rankName), ". Valid ranks: ", Rank.COMMAND_NAMES.joinToString())
        val server = sender.level().server
        val uuid = RegionsFeature.uuidForUsername(server, targetName)
            ?: return Paint.error("Player ", Paint.red(targetName), " not found or is offline")
        val target = server.playerList.getPlayer(uuid)
        if (target != null) {
            RankFeature.setRank(target, rank)
        } else {
            // Offline: the store-shaped write directly, since there is no
            // ServerPlayer to hang the player-shaped façade off of.
            checkNotNull(eu.mctraveler.MCTraveler.persistence).players.setRank(uuid, rank.name)
        }
        val displayName = target?.gameProfile?.name ?: RegionsFeature.usernameFor(server, uuid) ?: targetName
        return Paint.success(Paint.green(displayName), " is now a ", rank.chatColor(rank.label))
    }

    private inline fun reply(
        ctx: CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> Component?,
    ): Int {
        val player = ctx.source.playerOrException
        handler(player)?.let(player::sendSystemMessage)
        return Command.SINGLE_SUCCESS
    }
}
