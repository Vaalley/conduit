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
 * `/rank set <player> <rank|donator|no-donator>` — staff only (admin mode on), and works on an offline player by
 * name (the same known-username resolution `/rg add`/`/rg remove` use), since
 * granting Donator is exactly the kind of thing an admin does for someone who
 * isn't online right now.
 */
object RankCommands {

    private val onlinePlayerNames = SuggestionProvider<CommandSourceStack> { context, builder ->
        SharedSuggestionProvider.suggest(context.source.onlinePlayerNames, builder)
    }

    /** The Donator extra is set with the ranks' command, but is not a rank: `donator` gives it, `no-donator` takes it away. */
    private val DONATOR_NAMES = listOf("donator", "no-donator")

    private val rankNames = SuggestionProvider<CommandSourceStack> { context, builder ->
        // A moderator can only hand out ranks below their own.
        val grantable = context.source.player
            ?.takeUnless(StaffAccess::isFullAdmin)
            ?.let { Rank.entries.filter { rank -> !rank.outranks(Rank.TRAVELER) } }
            ?: Rank.entries
        SharedSuggestionProvider.suggest(grantable.map { it.name.lowercase() } + DONATOR_NAMES, builder)
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
        val donatorChange = when (rankName.lowercase()) {
            "donator" -> true
            "no-donator" -> false
            else -> null
        }
        val rank = if (donatorChange == null) {
            Rank.parse(rankName)
                ?: return Paint.error("Unknown rank ", Paint.red(rankName), ". Valid ranks: ", (Rank.COMMAND_NAMES + DONATOR_NAMES).joinToString())
        } else {
            null
        }
        val server = sender.level().server
        val uuid = RegionsFeature.uuidForUsername(server, targetName)
            ?: return Paint.error("Player ", Paint.red(targetName), " not found or is offline")
        // Admins set any rank; a moderator only moves players between the ranks below Moderator.
        if (!StaffAccess.isFullAdmin(sender)) {
            if (rank != null && rank.outranks(Rank.TRAVELER)) {
                return Paint.error("You can only set ranks below ", Rank.MODERATOR.chatColor(Rank.MODERATOR.label))
            }
            if (RankFeature.rankOf(uuid).outranks(Rank.TRAVELER)) {
                return Paint.error("You cannot change the rank of a ", Rank.MODERATOR.chatColor(Rank.MODERATOR.label))
            }
        }
        val target = server.playerList.getPlayer(uuid)
        val store = checkNotNull(eu.mctraveler.MCTraveler.persistence).players
        val displayName = target?.gameProfile?.name ?: RegionsFeature.usernameFor(server, uuid) ?: targetName
        if (donatorChange != null) {
            if (target != null) {
                RankFeature.setDonator(target, donatorChange)
            } else {
                // Offline: write the store directly (see below).
                if (store.rank(uuid).equals("DONATOR", ignoreCase = true)) store.setRank(uuid, Rank.TRAVELER.name)
                store.setDonator(uuid, donatorChange)
            }
            return if (donatorChange) {
                Paint.success(Paint.green(displayName), " is now a ", RankFeature.DONATOR_COLOR("Donator"))
            } else {
                Paint.success(Paint.green(displayName), " is no longer a ", RankFeature.DONATOR_COLOR("Donator"))
            }
        }
        checkNotNull(rank)
        if (target != null) {
            RankFeature.setRank(target, rank)
        } else {
            // Offline: the store-shaped write directly, since there is no
            // ServerPlayer to hang the player-shaped façade off of. An old stored
            // DONATOR rank becomes the extra on top of the new rank.
            if (store.rank(uuid).equals("DONATOR", ignoreCase = true)) store.setDonator(uuid, true)
            store.setRank(uuid, rank.name)
        }
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
