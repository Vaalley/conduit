package eu.mctraveler.cosmetic

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import eu.mctraveler.MCTraveler
import eu.mctraveler.economy.Economy
import eu.mctraveler.economy.Reasons
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId

object CosmeticFeature {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> registerCommands(dispatcher) }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("name")
                .executes { context ->
                    reply(context) {
                        Paint.usage(
                            "/name tag <text> | /name color <hex> | /name gradient <from> <to> | /name reset",
                        )
                    }
                }
                .then(
                    Commands.literal("tag")
                        .then(
                            Commands.argument("text", StringArgumentType.string())
                                .executes { context -> reply(context) { setTag(it, context) } },
                        ),
                )
                .then(
                    Commands.literal("color")
                        .then(
                            Commands.argument("hex", StringArgumentType.greedyString())
                                .executes { context -> reply(context) { setColor(it, context) } },
                        ),
                )
                .then(
                    Commands.literal("gradient")
                        .then(
                            Commands.argument("colors", StringArgumentType.greedyString())
                                .executes { context -> reply(context) { setGradient(it, context) } },
                        ),
                )
                .then(
                    Commands.literal("reset")
                        .executes { context -> reply(context) { reset(it, null) } }
                        .then(
                            Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes { context ->
                                    reply(context) { reset(it, profile(context)) }
                                },
                        ),
                ),
        )
    }

    private fun setTag(player: ServerPlayer, context: CommandContext<CommandSourceStack>): Component {
        val tag = NameStyle.validateTag(StringArgumentType.getString(context, "text"))
            ?: return Paint.error("Tag must be a single character or emoji")
        if (!charge(player, CosmeticFees.TAG, Reasons.FEE_COSMETIC_TAG)) {
            return insufficient(player, CosmeticFees.TAG, "for a tag")
        }
        players().setNameTag(player.uuid, tag)
        NameCosmetics.invalidate(player.uuid)
        return Paint.success("Your tag is now ", Paint.white(tag))
    }

    private fun setColor(player: ServerPlayer, context: CommandContext<CommandSourceStack>): Component {
        val hex = StringArgumentType.getString(context, "hex")
        val color = NameStyle.parseHex(hex) ?: return Paint.error("Use a hex colour like #ff8800")
        if (!charge(player, CosmeticFees.COLOR, Reasons.FEE_COSMETIC_COLOR)) {
            return insufficient(player, CosmeticFees.COLOR, "for a color")
        }
        players().setNameColor(player.uuid, "#%06x".format(color))
        players().setNameGradient(player.uuid, null, null)
        NameCosmetics.invalidate(player.uuid)
        return Paint.success("Your name is now ", NameCosmetics.forPlayer(player))
    }

    private fun setGradient(player: ServerPlayer, context: CommandContext<CommandSourceStack>): Component {
        val colors = StringArgumentType.getString(context, "colors").trim().split(Regex("\\s+"))
        if (colors.size != 2) return Paint.error("Usage: /name gradient <#from> <#to>")
        val fromInput = colors[0]
        val toInput = colors[1]
        val from = NameStyle.parseHex(fromInput)
        val to = NameStyle.parseHex(toInput)
        if (from == null || to == null) return Paint.error("Use a hex colour like #ff8800")
        if (!charge(player, CosmeticFees.GRADIENT, Reasons.FEE_COSMETIC_GRADIENT)) {
            return insufficient(player, CosmeticFees.GRADIENT, "for a gradient")
        }
        players().setNameColor(player.uuid, null)
        players().setNameGradient(player.uuid, "#%06x".format(from), "#%06x".format(to))
        NameCosmetics.invalidate(player.uuid)
        return Paint.success("Your name is now ", NameCosmetics.forPlayer(player))
    }

    private fun reset(sender: ServerPlayer, target: NameAndId?): Component {
        if (target != null) {
            RegionsFeature.adminGate(sender)?.let { return it }
        }
        val uuid = target?.id() ?: sender.uuid
        players().setNameTag(uuid, null)
        players().setNameColor(uuid, null)
        players().setNameGradient(uuid, null, null)
        NameCosmetics.invalidate(uuid)
        return if (target == null) Paint.success("Name cosmetics reset")
        else Paint.success("Name cosmetics reset for ", target.name())
    }

    private fun charge(player: ServerPlayer, amount: Long, reason: String): Boolean =
        economy().withdraw(player.uuid, amount, reason)

    private fun insufficient(player: ServerPlayer, amount: Long, suffix: String): Component =
        Paint.error(
            "You need ",
            Economy.format(amount),
            " ",
            suffix,
            " (you have ",
            Economy.format(economy().balanceOf(player.uuid)),
            ")",
        )

    private fun profile(context: CommandContext<CommandSourceStack>): NameAndId? =
        runCatching { GameProfileArgument.getGameProfiles(context, "player").firstOrNull() }.getOrNull()

    private inline fun reply(
        context: CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> Component,
    ): Int {
        val player = context.source.playerOrException
        player.sendSystemMessage(handler(player))
        return Command.SINGLE_SUCCESS
    }

    private fun players() = checkNotNull(MCTraveler.persistence) { "Cosmetics need the Persistence service" }.players

    private fun economy() = checkNotNull(MCTraveler.persistence) { "Cosmetics need the Persistence service" }.economy
}
