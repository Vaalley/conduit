package eu.mctraveler.rank

import eu.mctraveler.admin.StaffAccess
import eu.mctraveler.command.Audience
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
 * `/rank set <player> <rank>` — staff only (admin mode on), and works on an offline player by
 * name (the same known-username resolution `/rg add`/`/rg remove` use), since
 * granting Donator is exactly the kind of thing an admin does for someone who
 * isn't online right now.
 */
object RankCommands {

    private val onlinePlayerNames = SuggestionProvider<CommandSourceStack> { context, builder ->
        SharedSuggestionProvider.suggest(context.source.onlinePlayerNames, builder)
    }

    private val rankNames = SuggestionProvider<CommandSourceStack> { context, builder ->
        // A moderator can only hand out ranks below their own.
        val grantable = context.source.player
            ?.takeUnless(StaffAccess::isFullAdmin)
            ?.let { Rank.entries.filter { rank -> !rank.outranks(Rank.DONATOR) } }
            ?: Rank.entries
        SharedSuggestionProvider.suggest(grantable.map { it.name.lowercase() }, builder)
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("rank")
                .requires(Audience.STAFF.gate)
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
        // Admins set any rank; a moderator only moves players between the ranks below Moderator.
        if (!StaffAccess.isFullAdmin(sender)) {
            if (rank.outranks(Rank.DONATOR)) {
                return Paint.error("You can only set ranks below ", Rank.MODERATOR.chatColor(Rank.MODERATOR.label))
            }
            if (RankFeature.rankOf(uuid).outranks(Rank.DONATOR)) {
                return Paint.error("You cannot change the rank of a ", Rank.MODERATOR.chatColor(Rank.MODERATOR.label))
            }
        }
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
