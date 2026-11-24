package eu.mctraveler.admin

import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import eu.mctraveler.MCTraveler
import eu.mctraveler.rank.Rank
import eu.mctraveler.rank.RankFeature
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.server.permissions.PermissionSet
import net.minecraft.server.players.NameAndId

/**
 * Who is staff, and whether their staff powers are switched on.
 *
 * Two kinds of staff exist: *operators* (`ops.json`, the admins) and players with
 * the [Rank.MODERATOR] rank. Neither has any power until they run `/admin`
 * ([AdminFeature]); the switch is per player, in memory only, off by default and
 * off again after rejoining. While it is off an operator is an ordinary Donator.
 *
 * The same switch decides vanilla permissions too ([profilePermissions]), so
 * `/gamemode`, `/give` and friends stop working for an operator who is not in
 * admin mode, and a moderator in admin mode gets just the vanilla commands of
 * [MODERATOR_VANILLA_COMMANDS].
 */
object StaffAccess {
    /**
     * Commands a moderator may use while in admin mode: the vanilla ones they were given, and
     * Spark (the profiler), whose every subcommand is theirs.
     */
    val MODERATOR_VANILLA_COMMANDS: Set<String> =
        setOf("tp", "teleport", "kill", "weather", "time", "whitelist", "recipe", "spark")

    private val active: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Guards [hidesCommand]'s own probe against recursing into the mixin that calls it. */
    private val probing = ThreadLocal<Boolean>()

    fun isOperator(player: ServerPlayer): Boolean =
        player.level().server.playerList.isOp(player.nameAndId())

    private fun isOperator(server: MinecraftServer, id: NameAndId): Boolean = server.playerList.isOp(id)

    private fun isModerator(uuid: UUID): Boolean {
        val store = MCTraveler.persistence?.players ?: return false
        return RankFeature.rankOf(store, uuid) == Rank.MODERATOR
    }

    /** Eligible for `/admin`: an operator or a Moderator. */
    fun isStaff(player: ServerPlayer): Boolean = isOperator(player) || isModerator(player.uuid)

    /** Staff with admin mode on — what the old "is an admin" checks mean now. */
    fun isActive(player: ServerPlayer): Boolean = player.uuid in active && isStaff(player)

    /** An operator with admin mode on: the full set of admin commands, not just the moderator ones. */
    fun isFullAdmin(player: ServerPlayer): Boolean = player.uuid in active && isOperator(player)

    /** A moderator (not an operator) with admin mode on. */
    fun isActiveModeratorOnly(player: ServerPlayer): Boolean =
        player.uuid in active && !isOperator(player) && isModerator(player.uuid)

    fun isOn(uuid: UUID): Boolean = uuid in active

    /** Flips the switch with none of `/admin`'s side effects (no creative, no inventory swap) — for tests. */
    fun setOnForTests(uuid: UUID, on: Boolean) = setOn(uuid, on)

    internal fun setOn(uuid: UUID, on: Boolean) {
        if (on) active += uuid else active -= uuid
    }

    internal fun clear() = active.clear()

    /**
     * `MinecraftServer.getProfilePermissions` for [id]: operators are plain players
     * until they switch admin mode on, and a moderator in admin mode is lifted to
     * owner level, which is what Spark asks for by default (the commands beyond
     * [MODERATOR_VANILLA_COMMANDS] stay hidden by [hidesCommand], so the level opens
     * nothing else).
     */
    fun profilePermissions(
        server: MinecraftServer,
        id: NameAndId,
        vanilla: LevelBasedPermissionSet,
    ): LevelBasedPermissionSet = try {
        when {
            isOperator(server, id) -> if (id.id() in active) vanilla else LevelBasedPermissionSet.ALL
            id.id() in active && isModerator(id.id()) -> LevelBasedPermissionSet.OWNER
            else -> vanilla
        }
    } catch (failure: Exception) {
        MCTraveler.LOGGER.warn("Could not work out the permissions of ${id.name()}", failure)
        vanilla
    }

    /**
     * Whether [node], a command visible to [source], must be hidden: it is not
     * one of the commands this source's staff level allows. Runs for every node
     * the tree sync and the parser touch, so it bails out early.
     */
    fun hidesCommand(node: CommandNode<*>, source: CommandSourceStack): Boolean {
        if (node !is LiteralCommandNode<*>) return false
        val player = source.player ?: return false
        val name = node.literal
        // /trigger is for scoreboard triggers and has no use for anyone below a full admin.
        if (name == "trigger") return isRoot(node, source) && !isFullAdmin(player)
        if (player.uuid !in active || !isActiveModeratorOnly(player)) return false
        if (name in MODERATOR_VANILLA_COMMANDS || !isRoot(node, source)) return false
        if (probing.get() == true) return false
        // Anything an ordinary player may use stays; the rest of the vanilla
        // commands (gamemode, give, op, ...) are not for moderators.
        probing.set(true)
        try {
            @Suppress("UNCHECKED_CAST")
            val usableByEveryone = (node as CommandNode<CommandSourceStack>)
                .canUse(source.withPermission(PermissionSet.NO_PERMISSIONS))
            return !usableByEveryone
        } finally {
            probing.remove()
        }
    }

    private fun isRoot(node: CommandNode<*>, source: CommandSourceStack): Boolean =
        source.server.commands.dispatcher.root.getChild((node as LiteralCommandNode<*>).literal) === node
}
