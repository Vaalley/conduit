package eu.mctraveler.worlds

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import eu.mctraveler.text.Paint
import kotlin.math.floor
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/**
 * `/switch` — the merge's signpost. It used to Travel; now it answers.
 *
 * The merge is cold: players are told nothing beforehand and everything
 * afterwards, so the first thing a returning player does is the thing they have
 * always done. That makes this command the single place the explanation is
 * certain to reach everybody, and it is why the command is kept rather than
 * removed — an unknown-command error at that moment reads as "the server is
 * broken".
 *
 * It says four things, in this order and for this reason. That the Worlds have
 * merged, because that is the fact everything else follows from. Where the
 * player is standing, because it grounds the claim in something they can check
 * against their own screen. Where their other base went, when the merge recorded
 * one for them, because that is the only thing they cannot work out for
 * themselves. And that Bed and Spawn on the Teleportation Crystal are
 * untouched — which matters more than it looks, being the difference between a
 * player who thinks they are stranded and one who knows they have a way home.
 *
 * A player who never had a second base is told nothing about one, rather than
 * something empty; so is a player who joined after the merge, for whom the
 * artifact holds no record at all.
 */
object SwitchCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, banked: () -> BankedPositions) {
        dispatcher.register(
            Commands.literal("switch").executes { context ->
                val player = context.source.playerOrException
                player.sendSystemMessage(signpost(player, banked().of(player.uuid)))
                Command.SINGLE_SUCCESS
            },
        )
    }

    /**
     * The signpost as the player reads it. Built as one flat message with its
     * own newlines, the way the `/rg` help panel is: chat shows it as a single
     * block, so it cannot be broken up by anything arriving between lines.
     */
    private fun signpost(player: ServerPlayer, otherBase: OtherBase?): Component {
        val lines = mutableListOf<Any?>(
            Paint.darkGray("--["), " ", Paint.green.bold("One World"), " ", Paint.darkGray("]--"), "\n",
            Paint.gray(
                "Primary and Secondary have merged into one map. There is nowhere left to " +
                    "switch to — Secondary is somewhere you can walk to now.",
            ),
            "\n",
            Paint.gray(
                "You are at ",
                Paint.white(coordinates(player.x, player.y, player.z)),
                " in ",
                Paint.green(placeName(player.level().dimension().identifier().toString())),
                ".",
            ),
            "\n",
        )
        if (otherBase != null) {
            lines += Paint.gray(
                "Your other base — where you last stood in ",
                Paint.green(otherBase.worldName),
                " — is now at ",
                Paint.white(coordinates(otherBase.x, otherBase.y, otherBase.z)),
                " in ",
                Paint.green(placeName(otherBase.dimension)),
                ".",
            )
            lines += "\n"
        }
        lines += Paint.gray(
            Paint.aqua("Bed"),
            " and ",
            Paint.aqua("Spawn"),
            " on your Teleportation Crystal still work exactly as they always did.",
        )
        return Paint(*lines.toTypedArray())
    }

    /**
     * A place as the Portal wrote one: `x/y/z`, floored to the block the player
     * would read off their own debug screen and quote to a friend.
     */
    private fun coordinates(x: Double, y: Double, z: Double): String =
        "${floor(x).toInt()}/${floor(y).toInt()}/${floor(z).toInt()}"

    /**
     * A dimension as a player names it. Only the vanilla trio has a name worth
     * printing; anything else — the Embassies, and Secondary's own dimensions
     * until they are retired — is simply itself, which is the same stance the
     * Region layer takes on a dimension outside every World.
     */
    private fun placeName(dimension: String): String = PLACE_NAMES[dimension] ?: dimension

    private val PLACE_NAMES: Map<String, String> = mapOf(
        Level.OVERWORLD.identifier().toString() to "the Overworld",
        Level.NETHER.identifier().toString() to "the Nether",
        Level.END.identifier().toString() to "the End",
    )
}
