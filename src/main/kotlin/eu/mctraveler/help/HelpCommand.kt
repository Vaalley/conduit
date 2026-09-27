package eu.mctraveler.help

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import eu.mctraveler.command.Audience
import eu.mctraveler.command.CommandTree
import java.util.concurrent.CompletableFuture
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider

/**
 * `/help`: the paged list of the commands the reader can use, `/help <page>`,
 * `/help <command>` (with or without the slash, and through an alias) and
 * `/help markdown`. It replaces vanilla's `/help`. See `.scratch/help-rework/spec.md`.
 */
object HelpCommand {

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> register(dispatcher) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        // Re-registering an existing literal merges with vanilla's node, so vanilla's has to go first.
        CommandTree.removeRootCommands(dispatcher, HelpIndex.HELP)
        dispatcher.register(
            Commands.literal(HelpIndex.HELP)
                .executes { context -> show(context, null) }
                .then(
                    Commands.argument("topic", StringArgumentType.greedyString())
                        .suggests(::suggest)
                        .executes { context -> show(context, StringArgumentType.getString(context, "topic")) },
                ),
        )
    }

    private fun show(context: CommandContext<CommandSourceStack>, topic: String?): Int {
        val source = context.source
        val reply = HelpPages(indexFor(source)).render(topic)
        source.sendSystemMessage(reply.message)
        return if (reply.isError) 0 else 1
    }

    /** Only what the reader can use: command names, page numbers, and `markdown`. */
    private fun suggest(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val index = indexFor(context.source)
        val prefix = if (builder.remaining.startsWith("/")) "/" else ""
        val candidates = index.names() + (1..index.pageCount).map(Int::toString) + HelpPages.MARKDOWN
        return SharedSuggestionProvider.suggest(candidates.map { prefix + it }, builder)
    }

    private fun indexFor(source: CommandSourceStack): HelpIndex<CommandSourceStack> =
        HelpIndex.build(
            source.server.commands.dispatcher,
            source,
            viewerIsAdmin = source.player?.let(Audience.ADMIN::permits) ?: true,
            catalog = HelpCatalog.DEFAULT,
        )
}
