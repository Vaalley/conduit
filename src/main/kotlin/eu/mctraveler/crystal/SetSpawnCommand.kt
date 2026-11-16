package eu.mctraveler.crystal

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import eu.mctraveler.MCTraveler
import eu.mctraveler.command.Audience
import eu.mctraveler.command.CommandTree
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelData

/**
 * `/setspawn <spawn1|spawn2>` moves the real destination of `/spawn1` or `/spawn2` to
 * where the staff member stands. It replaces the vanilla `/setworldspawn`: the first spawn
 * also becomes the world spawn, so new players still arrive there.
 *
 * The spot is the middle of the block they stand on, and they face the nearest of
 * north, east, south or west.
 */
object SetSpawnCommand {

    private val spawnNames = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(CrystalSpawns.definitions().indices.map(CrystalSpawns::commandName), builder)
    }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            CommandTree.removeRootCommands(dispatcher, "setworldspawn")
            registerCommand(dispatcher)
        }
    }

    private fun registerCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("setspawn")
                .requires(Audience.STAFF.gate)
                .executes { ctx -> reply(ctx) { Paint.usage("/setspawn <spawn1|spawn2>") } }
                .then(
                    Commands.argument("spawn", StringArgumentType.word())
                        .suggests(spawnNames)
                        .executes { ctx -> reply(ctx) { set(it, StringArgumentType.getString(ctx, "spawn")) } },
                ),
        )
    }

    private fun set(player: ServerPlayer, name: String): Component {
        RegionsFeature.adminGate(player)?.let { return it }
        val names = CrystalSpawns.definitions().indices.map(CrystalSpawns::commandName)
        val index = names.indexOf(name.lowercase())
        if (index < 0) {
            return Paint.error("Unknown spawn ", Paint.red(name), ". Use ", names.joinToString(" or "))
        }
        val server = player.level().server
        val overworld = server.getLevel(Level.OVERWORLD) ?: server.overworld()
        if (player.level() !== overworld) {
            return Paint.error("The spawns are in the overworld; stand in the overworld to set one")
        }
        val old = CrystalSpawns.definitions()[index]
        val x = SpawnPlacement.centre(player.x)
        val z = SpawnPlacement.centre(player.z)
        val yaw = SpawnPlacement.snapYaw(player.yRot)
        CrystalSpawns.define(index, old.copy(x = x, y = player.y, z = z, yaw = yaw))
        if (index == 0) {
            // The first spawn doubles as the world spawn, which /setworldspawn used to set.
            val block = BlockPos(Mth.floor(x), Mth.floor(player.y), Mth.floor(z))
            overworld.respawnData = LevelData.RespawnData.of(Level.OVERWORLD, block, yaw, 0.0f)
        }
        MCTraveler.LOGGER.info("{} set {} to {} {} {} facing {}", player.gameProfile.name, names[index], x, player.y, z, SpawnPlacement.facingName(yaw))
        return Paint.success(
            Paint.green(names[index]), " is now at ",
            "${x.toInt()} ${player.y.toInt()} ${z.toInt()}", ", facing ", SpawnPlacement.facingName(yaw),
        )
    }

    private inline fun reply(ctx: CommandContext<CommandSourceStack>, handler: (ServerPlayer) -> Component?): Int {
        val player = ctx.source.playerOrException
        handler(player)?.let(player::sendSystemMessage)
        return Command.SINGLE_SUCCESS
    }
}
