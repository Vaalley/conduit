package eu.mctraveler.moderation

import com.mojang.brigadier.arguments.StringArgumentType
import eu.mctraveler.MCTraveler
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.time.Duration
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BucketItem
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult

object ActionRecorder {
    private val inspecting = mutableSetOf<java.util.UUID>()
    private data class PendingPlacement(
        val player: ServerPlayer,
        val level: Level,
        val clicked: net.minecraft.core.BlockPos,
        val target: net.minecraft.core.BlockPos,
        val beforeClicked: net.minecraft.world.level.block.state.BlockState,
        val beforeTarget: net.minecraft.world.level.block.state.BlockState,
    )
    private val pendingPlacements = mutableListOf<PendingPlacement>()

    fun register() {
        PlayerBlockBreakEvents.AFTER.register { level, player, pos, state, _ ->
            if (player is ServerPlayer) record(player, ActionType.BREAK, level, pos, state.block)
        }
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (player !is ServerPlayer) return@register InteractionResult.PASS
            if (inspecting.contains(player.uuid)) {
                show(player, level, hit.blockPos)
                return@register InteractionResult.FAIL
            }
            if (level.getBlockEntity(hit.blockPos) is Container) {
                record(player, ActionType.CONTAINER, level, hit.blockPos, level.getBlockState(hit.blockPos).block)
            }
            val stack = player.getItemInHand(hand)
            if (stack.item is BucketItem) {
                val type = if ((stack.item as BucketItem).content == net.minecraft.world.level.material.Fluids.EMPTY) {
                    ActionType.BUCKET_FILL
                } else {
                    ActionType.BUCKET_EMPTY
                }
                record(player, type, level, hit.blockPos, level.getBlockState(hit.blockPos).block)
            } else {
                queuePlacement(player, level, hit)
            }
            InteractionResult.PASS
        }
        AttackBlockCallback.EVENT.register { player, level, _, pos, _ ->
            if (player is ServerPlayer && inspecting.contains(player.uuid)) {
                show(player, level, pos)
                InteractionResult.FAIL
            } else {
                InteractionResult.PASS
            }
        }
        UseItemCallback.EVENT.register { player, level, hand ->
            if (player is ServerPlayer && inspecting.contains(player.uuid)) {
                show(player, level, player.blockPosition())
                InteractionResult.FAIL
            } else {
                InteractionResult.PASS
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> clear(handler.player.uuid) }
        ServerTickEvents.END_SERVER_TICK.register {
            flushPendingPlacements()
            MCTraveler.persistence?.actions?.flush()
        }
    }

    fun clear(uuid: java.util.UUID) {
        inspecting.remove(uuid)
    }

    fun queuePlacement(player: ServerPlayer, level: Level, hit: BlockHitResult) {
        val clicked = hit.blockPos
        val target = clicked.relative(hit.direction)
        pendingPlacements += PendingPlacement(
            player,
            level,
            clicked,
            target,
            level.getBlockState(clicked),
            level.getBlockState(target),
        )
    }

    fun flushPendingPlacements() {
        val pending = pendingPlacements.toList()
        pendingPlacements.clear()
        pending.forEach { placement ->
            val candidates = if (placement.beforeClicked.canBeReplaced()) {
                listOf(placement.clicked to placement.beforeClicked, placement.target to placement.beforeTarget)
            } else {
                listOf(placement.target to placement.beforeTarget, placement.clicked to placement.beforeClicked)
            }
            candidates.firstOrNull { (pos, before) ->
                val after = placement.level.getBlockState(pos)
                before.block != after.block &&
                    !after.isAir &&
                    (before.isAir || before.canBeReplaced())
            }?.let { (pos, _) ->
                record(
                    placement.player,
                    ActionType.PLACE,
                    placement.level,
                    pos,
                    placement.level.getBlockState(pos).block,
                )
            }
        }
    }

    fun registerCommands(
        dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>,
        gate: (CommandSourceStack) -> Boolean,
    ) {
        dispatcher.register(
            Commands.literal("inspect").requires(gate).executes { context ->
                val player = context.source.playerOrException
                if (!ModerationFeature.authorized(context.source)) return@executes 0
                if (!inspecting.add(player.uuid)) {
                    inspecting.remove(player.uuid)
                    player.sendSystemMessage(Paint.success("Inspect mode disabled"))
                } else {
                    player.sendSystemMessage(Paint.success("Inspect mode enabled"))
                }
                1
            },
        )
        dispatcher.register(
            Commands.literal("lookup").requires(gate)
                .then(
                    Commands.argument("tail", StringArgumentType.greedyString())
                        .executes { context -> lookupTail(context.source, StringArgumentType.getString(context, "tail")) },
                ),
        )
    }

    private fun lookupTail(source: CommandSourceStack, raw: String): Int {
        val args = raw.trim().split(Regex("\\s+"), limit = 3)
        return lookup(source, args[0], args.getOrNull(1), args.getOrNull(2))
    }

    private fun record(player: ServerPlayer, type: ActionType, level: Level, pos: net.minecraft.core.BlockPos, block: net.minecraft.world.level.block.Block) {
        MCTraveler.persistence?.actions?.record(
            type, player.uuid, player.gameProfile.name, RegionWorlds.legacyName(level.dimension()),
            pos.x, pos.y, pos.z, BuiltInRegistries.BLOCK.getKey(block).toString(),
        )
    }

    private fun show(player: ServerPlayer, level: Level, pos: net.minecraft.core.BlockPos) {
        val world = RegionWorlds.legacyName(level.dimension())
        val history = MCTraveler.persistence?.actions?.at(world, pos.x, pos.y, pos.z).orEmpty().take(10)
        if (history.isEmpty()) {
            player.sendSystemMessage(Paint.gray("No history for ${pos.x} ${pos.y} ${pos.z}"))
            return
        }
        history.forEach {
            player.sendSystemMessage(
                Paint.gray("${relative(it.t)} ${it.playerName} ${verb(it.type)} ${it.block}"),
            )
        }
    }

    private fun lookup(source: CommandSourceStack, raw: String, rawTime: String?, action: String?): Int {
        if (!ModerationFeature.authorized(source)) return 0
        val player = source.player ?: run {
            source.sendFailure(Paint.error("This command must be run by a player"))
            return 0
        }
        val since = System.currentTimeMillis() - (rawTime?.let {
            runCatching { Durations.parse(it) ?: Long.MAX_VALUE }.getOrElse {
                source.sendFailure(Paint.error("Invalid duration ", Paint.red(rawTime)))
                return 0
            }
        } ?: 86_400_000L)
        val type = when (action?.lowercase()) {
            null -> null
            "place" -> ActionType.PLACE
            "break" -> ActionType.BREAK
            "container" -> ActionType.CONTAINER
            "bucket" -> null
            else -> {
                source.sendFailure(Paint.error("Unknown action ", Paint.red(action)))
                return 0
            }
        }
        val targetUuid = if (!raw.startsWith("r:")) {
            source.server.playerList.getPlayerByName(raw)?.uuid
                ?: MCTraveler.persistence?.names?.uuidFor(raw)
                ?: run {
                    source.sendFailure(Paint.error("Unknown player ", Paint.red(raw)))
                    return 0
                }
        } else null
        val matching = MCTraveler.persistence?.actions?.all(since, type).orEmpty().filter {
            if (raw.startsWith("r:")) {
                val radius = raw.removePrefix("r:").toDoubleOrNull() ?: return@filter false
                val world = RegionWorlds.legacyName(player.level().dimension())
                it.world == world && player.distanceToSqr(it.x + .5, it.y + .5, it.z + .5) <= radius * radius
            } else {
                it.player == targetUuid
            }
        }.filter {
            action?.lowercase() != "bucket" || it.type == ActionType.BUCKET_FILL || it.type == ActionType.BUCKET_EMPTY
        }
        if (matching.isEmpty()) {
            source.sendSuccess({ Paint.gray("No matching actions") }, false)
            return 1
        }
        matching.take(15).forEach {
            source.sendSuccess({ Paint.gray("${relative(it.t)} ${it.playerName} ${verb(it.type)} ${it.block} at ${it.x} ${it.y} ${it.z}") }, false)
        }
        if (matching.size > 15) {
            source.sendSuccess({ Paint.gray("… and ${matching.size - 15} more") }, false)
        }
        return 1
    }

    private fun verb(type: ActionType) = when (type) {
        ActionType.PLACE -> "placed"
        ActionType.BREAK -> "broke"
        ActionType.CONTAINER -> "opened"
        ActionType.BUCKET_FILL -> "filled"
        ActionType.BUCKET_EMPTY -> "emptied"
    }

    private fun relative(time: Long): String {
        val seconds = Duration.ofMillis((System.currentTimeMillis() - time).coerceAtLeast(0)).seconds
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3_600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3_600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }
}
