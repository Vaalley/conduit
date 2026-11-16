package eu.mctraveler.admin

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import eu.mctraveler.MCTraveler
import eu.mctraveler.command.Audience
import eu.mctraveler.rank.RankFeature
import eu.mctraveler.text.Paint
import eu.mctraveler.vanish.VanishFeature
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

/**
 * `/admin` — the switch for staff powers — and `/back`, which returns a staff
 * member who used `/teleport` to where they stood before it.
 *
 * Switching on puts an operator in creative with an empty inventory (the real one
 * waits in [AdminInventory]); a moderator keeps survival and their items. Switching
 * off hands everything back and, if the player is left hanging in the air, puts
 * them on the first safe block below. The switch is never persisted: it is off
 * after a rejoin or a restart.
 */
object AdminFeature {
    /** Where a player stood before their last `/teleport`. */
    data class Spot(val dimension: ResourceKey<Level>, val x: Double, val y: Double, val z: Double, val yRot: Float, val xRot: Float)

    private val back = ConcurrentHashMap<UUID, Spot>()

    /** The spot a `/tp` is about to leave, held from the start of the command to its end. */
    private val pending = ConcurrentHashMap<UUID, Spot>()

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> registerCommands(dispatcher) }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> recoverAfterCrash(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            if (StaffAccess.isOn(player.uuid)) switchOff(player, announce = false)
            back.remove(player.uuid)
            pending.remove(player.uuid)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            // Before the players are saved, so nobody's real inventory is written out as empty.
            server.playerList.players.filter { StaffAccess.isOn(it.uuid) }.forEach { switchOff(it, announce = false) }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            StaffAccess.clear()
            back.clear()
            pending.clear()
        }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("admin")
                // Offered to operators and moderators only; hidden from everyone else.
                .requires { source -> source.player?.let(StaffAccess::isStaff) ?: true }
                .executes { context ->
                    val player = context.source.playerOrException
                    toggle(player)
                    Command.SINGLE_SUCCESS
                },
        )
        dispatcher.register(
            Commands.literal("back")
                .requires(Audience.STAFF.gate)
                .executes { context ->
                    val player = context.source.playerOrException
                    returnBack(player)
                    Command.SINGLE_SUCCESS
                },
        )
    }

    fun toggle(player: ServerPlayer) {
        if (!StaffAccess.isStaff(player)) {
            player.sendSystemMessage(Paint.error("You are not allowed to use admin mode"))
            return
        }
        if (StaffAccess.isOn(player.uuid)) {
            if (VanishFeature.isVanished(player)) {
                player.sendSystemMessage(Paint.error("Leave vanish with ", Paint.red("/vanish"), " before turning admin mode off"))
                return
            }
            switchOff(player, announce = true)
        } else {
            switchOn(player)
        }
    }

    private fun switchOn(player: ServerPlayer) {
        val operator = StaffAccess.isOperator(player)
        if (operator) {
            try {
                AdminInventory.stash(player)
            } catch (failure: Exception) {
                // Nothing was cleared: stay a normal player rather than risk the items.
                AdminInventory.log("Could not save ${player.gameProfile.name}'s inventory; admin mode stays off", failure)
                player.sendSystemMessage(Paint.error("Could not save your inventory, so admin mode stays off"))
                return
            }
            player.setGameMode(GameType.CREATIVE)
        }
        StaffAccess.setOn(player.uuid, true)
        RankFeature.resyncCommands(player)
        player.sendSystemMessage(
            Paint.red.bold("Admin mode is ON. Don't forget to turn it off with /admin when you are done!"),
        )
    }

    private fun switchOff(player: ServerPlayer, announce: Boolean) {
        val operator = StaffAccess.isOperator(player)
        StaffAccess.setOn(player.uuid, false)
        if (operator) {
            try {
                AdminInventory.restore(player)
            } catch (failure: Exception) {
                // The file is kept (it is only deleted after a successful restore), so a rejoin retries.
                AdminInventory.log("Could not restore ${player.gameProfile.name}'s inventory", failure)
            }
            // SURVIVAL, not the mode they entered with: inside a region the tracker turns it into adventure.
            player.setGameMode(GameType.SURVIVAL)
            if (announce) landSafely(player)
        }
        if (announce) {
            RankFeature.resyncCommands(player)
            player.sendSystemMessage(Paint.green("Admin mode is OFF."))
        }
    }

    /** After a crash the stashed inventory is still on disk; a joining player gets it back. */
    private fun recoverAfterCrash(player: ServerPlayer) {
        val server = player.level().server
        if (!AdminInventory.hasSaved(server, player.uuid)) return
        try {
            AdminInventory.restore(player)
            player.setGameMode(GameType.SURVIVAL)
            player.sendSystemMessage(Paint.gray("Your inventory from before admin mode was restored."))
        } catch (failure: Exception) {
            AdminInventory.log("Could not restore ${player.gameProfile.name}'s inventory on join", failure)
        }
    }

    // -- /back --

    /** Called before a command runs; remembers where a staff member stands if it is a teleport. */
    fun beforeCommand(player: ServerPlayer, command: String) {
        pending.remove(player.uuid)
        val root = command.trimStart('/').substringBefore(' ').lowercase()
        if (root != "tp" && root != "teleport") return
        if (!StaffAccess.isActive(player)) return
        pending[player.uuid] = spotOf(player)
    }

    /** Called after a command ran; if the teleport moved the player, that spot becomes their `/back`. */
    fun afterCommand(player: ServerPlayer) {
        val before = pending.remove(player.uuid) ?: return
        val now = spotOf(player)
        if (before.dimension != now.dimension || before.x != now.x || before.y != now.y || before.z != now.z) {
            back[player.uuid] = before
        }
    }

    private fun returnBack(player: ServerPlayer) {
        val spot = back.remove(player.uuid)
        if (spot == null) {
            player.sendSystemMessage(Paint.error("You have nowhere to go back to. ", Paint.red("/back"), " follows a /teleport"))
            return
        }
        val level = player.level().server.getLevel(spot.dimension)
        if (level == null) {
            player.sendSystemMessage(Paint.error("That world is not loaded any more"))
            return
        }
        player.teleportTo(level, spot.x, spot.y, spot.z, emptySet(), spot.yRot, spot.xRot, true)
        player.sendSystemMessage(Paint.green("Returned to where you were."))
    }

    private fun spotOf(player: ServerPlayer) =
        Spot(player.level().dimension(), player.x, player.y, player.z, player.yRot, player.xRot)

    fun backSpot(uuid: UUID): Spot? = back[uuid]

    // -- coming down --

    /** Leaves a player who is standing on something where they are; otherwise sets them on the first safe block below. */
    private fun landSafely(player: ServerPlayer) {
        val level = player.level()
        val feet = player.blockPosition()
        val target = firstSafeAtOrBelow(level, feet) ?: return
        if (target == feet) return
        player.teleportTo(level, player.x, target.y.toDouble(), player.z, emptySet(), player.yRot, player.xRot, true)
    }

    /** The lowest [BlockPos] at or under [from] a player can stand in: solid floor, two free blocks. */
    fun firstSafeAtOrBelow(level: ServerLevel, from: BlockPos): BlockPos? {
        var y = from.y
        while (y > level.minY) {
            val feet = BlockPos(from.x, y, from.z)
            if (canStandAt(level, feet)) return feet
            y--
        }
        return null
    }

    private fun canStandAt(level: ServerLevel, feet: BlockPos): Boolean {
        val floor = level.getBlockState(feet.below())
        if (!floor.isFaceSturdy(level, feet.below(), Direction.UP)) return false
        if (floor.`is`(Blocks.MAGMA_BLOCK) || floor.`is`(Blocks.CACTUS) || floor.`is`(BlockTags.CAMPFIRES)) return false
        return passable(level, feet) && passable(level, feet.above())
    }

    private fun passable(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return state.getCollisionShape(level, pos).isEmpty &&
            state.fluidState.isEmpty &&
            !state.`is`(BlockTags.FIRE) &&
            !state.`is`(Blocks.COBWEB) &&
            !state.`is`(Blocks.POWDER_SNOW)
    }
}
