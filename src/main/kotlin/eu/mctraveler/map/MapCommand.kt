package eu.mctraveler.map

import com.mojang.brigadier.Command
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands
import java.util.Locale

fun buildMapUrl(x: Double, z: Double): String =
    "https://map.mctraveler.eu/#x=%.2f&z=%.2f&zoom=1".format(Locale.ROOT, x, z)

object MapCommand {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("map").executes { ctx ->
                    val player = ctx.source.playerOrException
                    val url = buildMapUrl(player.x, player.z)
                    player.sendSystemMessage(Paint.info("Map: ", Paint.aqua.underline.opensUrl(url)(url)))
                    Command.SINGLE_SUCCESS
                },
            )
        }
    }

    fun buildMapUrl(x: Double, z: Double): String = eu.mctraveler.map.buildMapUrl(x, z)
}
