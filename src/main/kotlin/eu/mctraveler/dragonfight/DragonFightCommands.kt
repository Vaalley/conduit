package eu.mctraveler.dragonfight

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.server.level.ServerPlayer

object DragonFightCommands {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, service: () -> DragonFightService) {
        val target = StringArgumentType.word()
        dispatcher.register(
            Commands.literal("dragonfight")
                .executes { context -> reply(context) { service().start(it) } }
                .then(
                    Commands.literal("confirm")
                        .executes { context -> reply(context) { service().confirm(it) } },
                )
                .then(
                    Commands.literal("leave")
                        .executes { context -> reply(context) { service().leave(it) } },
                )
                .then(
                    Commands.literal("invite")
                        .then(
                            Commands.argument("player", target)
                                .suggests { context, builder ->
                                    SharedSuggestionProvider.suggest(
                                        context.source.server.playerList.players
                                            .map { it.gameProfile.name }
                                            .filter { it != context.source.player?.gameProfile?.name },
                                        builder,
                                    )
                                }
                                .executes { context ->
                                    val sender = context.source.playerOrException
                                    val name = StringArgumentType.getString(context, "player")
                                    val targetPlayer = context.source.server.playerList.getPlayerByName(name)
                                        ?: return@executes sender.sendSystemMessage(Paint.error("Unknown player ", Paint.red(name))).let { Command.SINGLE_SUCCESS }
                                    reply(sender) { service().invite(it, targetPlayer) }
                                },
                        ),
                )
                .then(
                    Commands.literal("join")
                        .then(
                            Commands.argument("owner", target)
                                .suggests { context, builder ->
                                    SharedSuggestionProvider.suggest(
                                        service().state.arenas().keys.mapNotNull {
                                            context.source.server.playerList.getPlayer(it)?.gameProfile?.name
                                        },
                                        builder,
                                    )
                                }
                                .executes { context ->
                                    reply(context) {
                                        service().join(it, StringArgumentType.getString(context, "owner"))
                                    }
                                },
                        ),
                )
                .then(
                    Commands.literal("reset")
                        .then(
                            Commands.argument("player", target)
                                .suggests { context, builder ->
                                    SharedSuggestionProvider.suggest(
                                        context.source.server.playerList.players.map { it.gameProfile.name },
                                        builder,
                                    )
                                }
                                .executes { context ->
                                    val admin = context.source.playerOrException
                                    RegionsFeature.adminGate(admin)?.let {
                                        admin.sendSystemMessage(it)
                                        return@executes Command.SINGLE_SUCCESS
                                    }
                                    val name = StringArgumentType.getString(context, "player")
                                    admin.sendSystemMessage(service().reset(admin, name))
                                    Command.SINGLE_SUCCESS
                                },
                        ),
                ),
        )
    }

    private inline fun reply(
        context: CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> net.minecraft.network.chat.Component?,
    ): Int = reply(context.source.playerOrException, handler)

    private inline fun reply(player: ServerPlayer, handler: (ServerPlayer) -> net.minecraft.network.chat.Component?): Int {
        handler(player)?.let(player::sendSystemMessage)
        return Command.SINGLE_SUCCESS
    }
}
