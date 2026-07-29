package eu.mctraveler.worlds

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import eu.mctraveler.text.Paint
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

/**
 * `/switch` — Travel to the other World, with the Portal's exact messages
 * (SwitchFeature, inventory §2.5): the gray "Switching to <green name>..."
 * announcement first, then the move; a failure is reported as
 * `ERROR Failed to switch server: <error>`, wording kept from the Portal.
 */
object SwitchCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, worlds: () -> Worlds) {
        dispatcher.register(
            Commands.literal("switch").executes { context ->
                val player = context.source.playerOrException
                val destination = worlds().switchDestination(player)
                player.sendSystemMessage(
                    Paint.gray("Switching to ", Paint.green(destination.displayName), "..."),
                )
                try {
                    worlds().travel(player, destination)
                    Command.SINGLE_SUCCESS
                } catch (failure: Exception) {
                    player.sendSystemMessage(
                        Paint.error("Failed to switch server: ", failure.message ?: failure.toString()),
                    )
                    0
                }
            },
        )
    }
}
