package eu.mctraveler.crystal

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
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
 * `/set-teleportation-crystal-energy <energy> [player]` (spec User Story 37).
 * `/recharge-teleportation-crystal [player]` recharges a player's pool fully.
 *
 * Nucleus sent both the bounds error and the success line to the *target*,
 * leaving the sender staring at nothing after setting someone else's energy —
 * and the success line names the target in the third person, so it was plainly
 * written for the sender. Intent Parity: it all goes to the sender
 * (deviation 5).
 */
object CrystalCommands {

    private const val NAME = "set-teleportation-crystal-energy"
    private const val RECHARGE_NAME = "recharge-teleportation-crystal"

    private val onlinePlayerNames = SuggestionProvider<CommandSourceStack> { context, builder ->
        SharedSuggestionProvider.suggest(context.source.onlinePlayerNames, builder)
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal(NAME)
                // Usage comes before the admin gate (house rule): telling
                // someone how to type a command gives nothing away.
                .executes { ctx -> reply(ctx) { Paint.usage("/$NAME <energy> [player]") } }
                .then(
                    Commands.argument("energy", IntegerArgumentType.integer())
                        .executes { ctx ->
                            reply(ctx) { sender -> setEnergy(sender, energyArg(ctx), sender.gameProfile.name) }
                        }
                        .then(
                            Commands.argument("player", StringArgumentType.word())
                                .suggests(onlinePlayerNames)
                                .executes { ctx ->
                                    reply(ctx) { sender ->
                                        setEnergy(sender, energyArg(ctx), StringArgumentType.getString(ctx, "player"))
                                    }
                                },
                        ),
                ),
        )
        dispatcher.register(
            Commands.literal(RECHARGE_NAME)
                .executes { ctx -> reply(ctx) { sender -> recharge(sender, sender.gameProfile.name) } }
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests(onlinePlayerNames)
                        .executes { ctx ->
                            reply(ctx) { sender ->
                                recharge(sender, StringArgumentType.getString(ctx, "player"))
                            }
                        },
                ),
        )
    }

    private fun energyArg(ctx: CommandContext<CommandSourceStack>): Int =
        IntegerArgumentType.getInteger(ctx, "energy")

    /**
     * Sets [targetName]'s energy on [sender]'s behalf, returning what the
     * sender should be told.
     */
    private fun setEnergy(sender: ServerPlayer, energy: Int, targetName: String): Component {
        RegionsFeature.adminGate(sender)?.let { return it }
        // Bounds before the lookup, so a typo in the energy is not reported as
        // a missing player.
        if (energy < 0 || energy > CrystalEnergy.MAX_ENERGY) {
            return Paint.error("Energy must be between 0 and ${CrystalEnergy.MAX_ENERGY}")
        }
        val target = exactPlayer(sender.level().server, targetName)
            ?: return notOnline(targetName)
        CrystalEnergy.setEnergy(target, energy)
        return Paint.success(
            Paint.green(target.gameProfile.name),
            " now has ",
            Paint.green(energy),
            " energy",
        )
    }

    private fun recharge(sender: ServerPlayer, targetName: String): Component {
        RegionsFeature.adminGate(sender)?.let { return it }
        val target = exactPlayer(sender.level().server, targetName)
            ?: return notOnline(targetName)
        CrystalEnergy.setEnergy(target, CrystalEnergy.MAX_ENERGY)
        return Paint.success(
            Paint.green(target.gameProfile.name),
            " now has ",
            Paint.green(CrystalEnergy.MAX_ENERGY),
            " energy",
        )
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
