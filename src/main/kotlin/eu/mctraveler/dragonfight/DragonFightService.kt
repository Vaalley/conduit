package eu.mctraveler.dragonfight

import eu.mctraveler.MCTraveler
import eu.mctraveler.mixin.EnderDragonFightAccessor
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import eu.mctraveler.worlds.Landing
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature
import net.minecraft.world.level.levelgen.feature.EndPodiumFeature
import net.minecraft.world.level.portal.TeleportTransition

/**
 * Owns the runtime arenas, command flow, and cleanup work for dragon fights.
 */
class DragonFightService(
    private val server: MinecraftServer,
    val state: DragonFightState,
) {
    data class Arena(
        val owner: UUID,
        val level: ServerLevel,
        val createdAt: Long,
        val guests: MutableSet<UUID>,
    )

    private val arenas = LinkedHashMap<UUID, Arena>()
    private val pendingConfirm = HashMap<UUID, Int>()
    private val pendingDeletion = LinkedHashSet<UUID>()
    private val gatewayMessages = HashMap<UUID, Int>()
    private val pendingEggs = HashMap<UUID, ItemStack>()

    fun requirementsGate(player: ServerPlayer): Component? {
        val settings = DragonFightConfig.settings()
        val endermen = player.stats.getValue(Stats.ENTITY_KILLED.get(entityType("enderman")))
        val blazes = player.stats.getValue(Stats.ENTITY_KILLED.get(entityType("blaze")))
        if (endermen >= settings.requiredEndermanKills && blazes >= settings.requiredBlazeKills) return null
        return Paint.error(
            "Prove yourself first: endermen ",
            Paint.gold(endermen),
            "/",
            Paint.gold(settings.requiredEndermanKills),
            ", blazes ",
            Paint.gold(blazes),
            "/",
            Paint.gold(settings.requiredBlazeKills),
        )
    }

    fun start(player: ServerPlayer): Component? {
        val currentOwner = ArenaLevels.owner(player.level().dimension())
        if (currentOwner != null) {
            return if (currentOwner == player.uuid) {
                Paint.error("You are already in your dragon fight")
            } else {
                Paint.error("Leave this dragon fight first")
            }
        }
        val own = arenas[player.uuid]
        if (own != null) {
            return enter(player, own)
        }
        if (state.isCompleted(player.uuid)) {
            return Paint.error("You have already freed the End. Try /rtp end")
        }
        requirementsGate(player)?.let { return it }
        pendingConfirm[player.uuid] = server.tickCount
        player.sendSystemMessage(
            Paint(
                Paint.warning("This will begin a private dragon fight for you."),
                "\n",
                Paint.gray("You will be alone in a private End. The border keeps you near the island."),
                "\n",
                Paint.gray("The dragon is real. If you die, you return home and /dragonfight brings you back."),
                "\n",
                Paint.gray("Leaving through the exit portal ends the fight forever and deletes the world."),
                "\n",
                Paint.gray("The dragon egg is yours, and /rtp end is yours afterward."),
                "\n",
                Paint.gold.runs("/dragonfight confirm")("[Confirm]"),
            ),
        )
        return null
    }

    fun confirm(player: ServerPlayer): Component? {
        val started = pendingConfirm[player.uuid]
            ?: return Paint.usage("/dragonfight")
        pendingConfirm.remove(player.uuid)
        if (server.tickCount - started > 1200) return Paint.usage("/dragonfight")
        if (ArenaLevels.owner(player.level().dimension()) != null) {
            return Paint.error("Leave this dragon fight first")
        }
        if (state.isCompleted(player.uuid)) {
            return Paint.error("You have already freed the End. Try /rtp end")
        }
        val existing = arenas[player.uuid]
        if (existing != null) {
            enter(player, existing)
            return null
        }
        val settings = DragonFightConfig.settings()
        val level = ArenaLevels.create(server, player.uuid, settings.borderRadius)
        val arena = Arena(player.uuid, level, System.currentTimeMillis(), LinkedHashSet())
        arenas[player.uuid] = arena
        state.putArena(player.uuid, DragonFightState.ArenaRecord(arena.createdAt, emptySet()))
        enter(player, arena)
        return null
    }

    fun enter(player: ServerPlayer, arena: Arena): Component? {
        val currentOwner = ArenaLevels.owner(player.level().dimension())
        if (currentOwner != null) return Paint.error("Leave this dragon fight first")
        if (player.uuid != arena.owner && player.uuid !in arena.guests) {
            return Paint.error("You are not invited to this dragon fight")
        }
        val pos = ServerLevel.END_SPAWN_POINT
        EndPlatformFeature.createEndPlatform(arena.level, pos.below(), true)
        Landing(arena.level, pos.x + 0.5, pos.y - 1.0, pos.z + 0.5, Direction.WEST.toYRot(), 0.0f).send(player)
        return null
    }

    fun leave(player: ServerPlayer): Component? {
        ArenaLevels.owner(player.level().dimension())
            ?: return Paint.error("You are not in a dragon fight")
        respawn(player)
        return null
    }

    fun invite(owner: ServerPlayer, target: ServerPlayer): Component? {
        val arena = arenas[owner.uuid] ?: return Paint.error("You do not have a dragon fight")
        if (target.uuid == owner.uuid) return Paint.error("You cannot invite yourself")
        arena.guests += target.uuid
        state.putArena(owner.uuid, DragonFightState.ArenaRecord(arena.createdAt, arena.guests.toSet()))
        target.sendSystemMessage(
            Paint.gray(
                owner.gameProfile.name,
                " invited you to their dragon fight — /dragonfight join ",
                owner.gameProfile.name,
            ),
        )
        return Paint.success("Invited ", Paint.green(target.gameProfile.name))
    }

    fun join(player: ServerPlayer, ownerName: String): Component? {
        val owner = server.playerList.getPlayerByName(ownerName)?.uuid
            ?: MCTraveler.persistence?.names?.uuidFor(ownerName)
            ?: return Paint.error("Unknown player ", Paint.red(ownerName))
        val arena = arenas[owner] ?: return Paint.error("That dragon fight does not exist")
        if (player.uuid !in arena.guests) return Paint.error("You are not invited to this dragon fight")
        return enter(player, arena)
    }

    fun reset(admin: ServerPlayer, name: String): Component {
        val target = server.playerList.getPlayerByName(name)
        val uuid = target?.uuid ?: MCTraveler.persistence?.names?.uuidFor(name)
            ?: return Paint.error("Unknown player ", Paint.red(name))
        pendingConfirm.remove(uuid)
        state.forgetCompleted(uuid)
        val arena = arenas[uuid]
        if (arena != null) {
            pendingDeletion.remove(uuid)
            arena.level.players().toList().forEach { evict(it) }
            if (arena.level.players().isEmpty()) {
                deleteArena(uuid, arena)
            } else {
                pendingDeletion += uuid
            }
        }
        return Paint.success(
            Paint.green(target?.gameProfile?.name ?: MCTraveler.persistence?.names?.usernameFor(uuid) ?: name),
            " can fight the dragon again",
        )
    }

    fun exitPortal(level: ServerLevel, entity: Entity): TeleportTransition? {
        if (entity !is ServerPlayer) return null
        val owner = ArenaLevels.owner(level.dimension()) ?: return null
        val transition = entity.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING)
        if (arenas[owner] == null) return transition
        if (entity.uuid != owner) return transition
        state.markCompleted(owner)
        takeDragonEgg(level, entity)
        pendingDeletion += owner
        entity.sendSystemMessage(Paint.success("You have freed the End. /rtp end is yours now"))
        return transition
    }

    fun gatewayBlocked(level: ServerLevel, entity: Entity) {
        val player = entity as? ServerPlayer ?: return
        val now = server.tickCount
        if (now - (gatewayMessages[player.uuid] ?: Int.MIN_VALUE) < 60) return
        gatewayMessages[player.uuid] = now
        player.sendSystemMessage(Paint.error("The gateways are sealed here"))
    }

    fun tick() {
        if (pendingDeletion.isNotEmpty()) {
            pendingDeletion.toList().forEach { owner ->
                val arena = arenas[owner] ?: return@forEach
                arena.level.players().toList().forEach { player ->
                    evict(player)
                    player.sendSystemMessage(Paint.error("This dragon fight is over"))
                }
                if (arena.level.players().isEmpty()) deleteArena(owner, arena)
            }
        }
        pendingEggs.entries.removeIf { (uuid, stack) ->
            val player = server.playerList.getPlayer(uuid) ?: return@removeIf false
            if (ArenaLevels.owner(player.level().dimension()) != null) return@removeIf false
            if (!player.inventory.add(stack)) player.drop(stack, false, false)
            true
        }
        if (server.tickCount % 1200 != 0) return
        val expiry = System.currentTimeMillis() - DragonFightConfig.settings().arenaTtlMillis
        arenas.values.filter { it.createdAt < expiry && it.level.players().isEmpty() }
            .forEach { pendingDeletion += it.owner }
    }

    fun clear() {
        arenas.clear()
        pendingConfirm.clear()
        pendingDeletion.clear()
        gatewayMessages.clear()
        pendingEggs.clear()
    }

    private fun respawn(player: ServerPlayer) {
        player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING)?.let(player::teleport)
    }

    private fun evict(player: ServerPlayer) = respawn(player)

    private fun deleteArena(owner: UUID, arena: Arena) {
        ArenaLevels.delete(server, arena.level)
        arenas.remove(owner)
        pendingDeletion.remove(owner)
        state.removeArena(owner)
    }

    private fun takeDragonEgg(level: ServerLevel, player: ServerPlayer) {
        val fight = level.dragonFight ?: return
        val origin = (fight as EnderDragonFightAccessor).`mctraveler$exitPortalLocation`()
            ?: level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, EndPodiumFeature.getLocation(BlockPos.ZERO)).below()
        for (x in origin.x - 2..origin.x + 2) {
            for (z in origin.z - 2..origin.z + 2) {
                for (y in origin.y..origin.y + 12) {
                    val pos = BlockPos(x, y, z)
                    if (!level.getBlockState(pos).`is`(Blocks.DRAGON_EGG)) continue
                    level.removeBlock(pos, false)
                    val egg = ItemStack(Items.DRAGON_EGG)
                    if (!player.inventory.add(egg)) pendingEggs[player.uuid] = egg
                    return
                }
            }
        }
    }

    private fun entityType(path: String): EntityType<*> =
        BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(path))
}
