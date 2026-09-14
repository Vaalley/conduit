package eu.mctraveler.economy

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import eu.mctraveler.MCTraveler
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId

object EconomyFeature {
    private val players = SuggestionProvider<CommandSourceStack> { context, builder ->
        net.minecraft.commands.SharedSuggestionProvider.suggest(
            context.source.server.playerList.players.map { it.gameProfile.name },
            builder,
        )
    }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> registerCommands(dispatcher) }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("balance")
                .executes { context -> reply(context) { ownBalance(it) } }
                .then(
                    Commands.argument("player", GameProfileArgument.gameProfile())
                        .suggests(players)
                        .executes { context ->
                            reply(context) { sender ->
                                RegionsFeature.adminGate(sender)?.let { return@reply it }
                                val profile = profile(context) ?: return@reply Paint.error("Player not found")
                                Paint.balance(
                                    profile.name(),
                                    " has ",
                                    Economy.format(balance(profile.id())),
                                )
                            }
                        },
                )
                .then(
                    Commands.literal("set")
                        .then(
                            Commands.argument("player", GameProfileArgument.gameProfile())
                                .suggests(players)
                                .then(
                                    Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes { context ->
                                            reply(context) { sender ->
                                                set(sender, profile(context), amount(context))
                                            }
                                        },
                                ),
                        ),
                )
                .then(
                    Commands.literal("give")
                        .then(
                            Commands.argument("player", GameProfileArgument.gameProfile())
                                .suggests(players)
                                .then(
                                    Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes { context ->
                                            reply(context) { sender ->
                                                give(sender, profile(context), amount(context))
                                            }
                                        },
                                ),
                        ),
                ),
        )

        dispatcher.register(
            Commands.literal("pay")
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests(players)
                        .then(
                            Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes { context ->
                                    reply(context) { sender ->
                                        pay(sender, StringArgumentType.getString(context, "player"), amount(context))
                                    }
                                },
                        ),
                ),
        )
    }

    private fun ownBalance(player: ServerPlayer): Component =
        Paint.balance("Your balance is ", Economy.format(balance(player.uuid)))

    private fun set(sender: ServerPlayer, profile: NameAndId?, amount: Long): Component {
        RegionsFeature.adminGate(sender)?.let { return it }
        if (profile == null) return Paint.error("Player not found")
        store().setBalance(profile.id(), amount)
        return Paint.success(profile.name(), " now has ", Economy.format(amount))
    }

    private fun give(sender: ServerPlayer, profile: NameAndId?, amount: Long): Component {
        RegionsFeature.adminGate(sender)?.let { return it }
        if (profile == null) return Paint.error("Player not found")
        Economy.deposit(store(), profile.id(), amount)
        return Paint.success(profile.name(), " received ", Economy.format(amount))
    }

    private fun pay(sender: ServerPlayer, targetName: String, amount: Long): Component {
        val target = sender.level().server.playerList.getPlayerByName(targetName)
            ?: return Paint.error("Player ", Paint.red(targetName), " is not online")
        if (target.uuid == sender.uuid) return Paint.error("You can't pay yourself")
        if (!Economy.withdraw(store(), sender.uuid, amount)) {
            return Paint.error("You don't have enough money")
        }
        Economy.deposit(store(), target.uuid, amount)
        target.sendSystemMessage(Paint.balance(sender.gameProfile.name, " paid you ", Economy.format(amount)))
        return Paint.success("Paid ", target.gameProfile.name, " ", Economy.format(amount))
    }

    private fun balance(uuid: java.util.UUID): Long = Economy.balanceOf(store(), uuid)

    private fun profile(context: CommandContext<CommandSourceStack>): NameAndId? =
        runCatching { GameProfileArgument.getGameProfiles(context, "player").firstOrNull() }.getOrNull()

    private fun amount(context: CommandContext<CommandSourceStack>): Long =
        LongArgumentType.getLong(context, "amount")

    private inline fun reply(
        context: CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> Component,
    ): Int {
        val player = context.source.playerOrException
        player.sendSystemMessage(handler(player))
        return Command.SINGLE_SUCCESS
    }

    private fun store() =
        checkNotNull(MCTraveler.persistence) { "the Economy needs the Persistence service" }.players
}
