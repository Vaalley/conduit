package eu.mctraveler.chat

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import eu.mctraveler.command.CommandTree
import eu.mctraveler.text.Paint
import java.util.UUID
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.server.level.ServerPlayer

/**
 * Private messaging, ported from the Portal's ChatFeature (spec stories 12-14):
 * `/msg <player> <message>` with the sender→target line shown identically to both
 * parties, `/reply` (`/r`) answering the last person who messaged you, and vanilla's
 * `/tell` and `/w` as true aliases of `/msg` — the vanilla commands themselves are
 * replaced, exactly as the Portal removed them from its command tree.
 */
object PrivateMessages {

    /**
     * Who each player's `/reply` goes to, by UUID. In-memory only, updated by `/msg`
     * in both directions — and never by `/reply` itself (a Portal quirk we preserve).
     */
    private val replyPartners = HashMap<UUID, UUID>()

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> registerCommands(dispatcher) }
        // Your own reply entry dies with your session (the Portal kept it on the connection
        // object). Entries pointing at you stay, so your partners get the gone-offline error.
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            replyPartners.remove(handler.player.uuid)
        }
    }

    /** Tab-completes online player names, like the Portal's onlinePlayer parser. */
    private val onlinePlayerNames = SuggestionProvider<CommandSourceStack> { context, builder ->
        SharedSuggestionProvider.suggest(context.source.onlinePlayerNames, builder)
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        CommandTree.removeRootCommands(dispatcher, "msg", "tell", "w")

        val msg = dispatcher.register(
            Commands.literal("msg").then(
                Commands.argument("target", StringArgumentType.word())
                    .suggests(onlinePlayerNames)
                    .then(
                        Commands.argument("message", StringArgumentType.greedyString())
                            .executes { context ->
                                msg(
                                    context.source,
                                    StringArgumentType.getString(context, "target"),
                                    StringArgumentType.getString(context, "message"),
                                )
                            }
                    )
            )
        )
        dispatcher.register(Commands.literal("tell").redirect(msg))
        dispatcher.register(Commands.literal("w").redirect(msg))

        val reply = dispatcher.register(
            Commands.literal("reply").then(
                Commands.argument("message", StringArgumentType.greedyString())
                    .executes { context ->
                        reply(context.source, StringArgumentType.getString(context, "message"))
                    }
            )
        )
        dispatcher.register(Commands.literal("r").redirect(reply))
    }

    private fun msg(source: CommandSourceStack, targetName: String, message: String): Int {
        val sender = source.playerOrException
        val target = source.server.playerList.getPlayerByName(targetName)
        if (target == null) {
            // The Portal's player-not-found error (feature-api/command.ts onlinePlayer parser).
            sender.sendSystemMessage(Paint.gray("Player ", Paint.red(targetName), " not found or is offline"))
            return 0
        }
        if (target === sender) {
            sender.sendSystemMessage(Paint.error("You can't send a message to yourself"))
            return 0
        }
        replyPartners[target.uuid] = sender.uuid
        replyPartners[sender.uuid] = target.uuid
        deliver(sender, target, message)
        return 1
    }

    private fun reply(source: CommandSourceStack, message: String): Int {
        val sender = source.playerOrException
        val partner = replyPartners[sender.uuid]
        if (partner == null) {
            sender.sendSystemMessage(Paint.error("You have no-one to reply to"))
            return 0
        }
        val target = source.server.playerList.getPlayer(partner)
        if (target == null) {
            sender.sendSystemMessage(Paint.error("The player you were messaging is no longer online"))
            return 0
        }
        deliver(sender, target, message)
        return 1
    }

    /** Sends the Portal's private-message line — identical for both parties — to each of them. */
    private fun deliver(sender: ServerPlayer, target: ServerPlayer, message: String) {
        val line = Paint(
            Paint.green(sender.gameProfile.name), " ", Paint.gray("→"), " ",
            Paint.green(target.gameProfile.name), ": ", message,
        )
        target.sendSystemMessage(line)
        sender.sendSystemMessage(line)
    }
}
