package eu.mctraveler.worlds

import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/**
 * Somewhere to put a player, right now: a level that is loaded, a position in
 * it, and a facing.
 *
 * This is the shape every teleport in the mod ends at, so the teleport itself
 * lives here ([send]) rather than being spelled out at each call site. Vanilla's
 * `teleportTo` takes eight arguments, five of which are always the same three
 * things — an empty relative-movement set, the caller's yaw and pitch, and "do
 * not keep the camera" — and a copy of that line per destination is a copy of
 * five chances to get one of them wrong.
 *
 * Held only for as long as it takes to arrive. Anything that remembers a place
 * *across* time wants [Waypoint] instead, which names its dimension rather than
 * holding a level open.
 */
data class Landing(
    val level: ServerLevel,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {

    /**
     * Puts [player] here, returning what vanilla returns: false when the
     * teleport was refused (a player being removed, most often).
     */
    fun send(player: ServerPlayer): Boolean =
        player.teleportTo(level, x, y, z, emptySet(), yaw, pitch, false)

    companion object {

        /** Exactly where [player] is standing and looking, this moment. */
        fun of(player: ServerPlayer): Landing =
            Landing(player.level(), player.x, player.y, player.z, player.yRot, player.xRot)
    }
}

/**
 * Somewhere as it is *remembered*: the dimension by key rather than a loaded
 * level, plus the same position and facing a [Landing] carries.
 *
 * The distinction is not decoration. A remembered place outlives the level it
 * names — a server can be restarted, a dimension can stop being registered —
 * so resolving is allowed to fail, and [resolve] says so by returning null. A
 * stored [Landing] could never express that, and would pin a level object for
 * as long as it was held.
 */
data class Waypoint(
    val dimension: ResourceKey<Level>,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {

    /** This place as somewhere to go, or null if [server] has no such level. */
    fun resolve(server: MinecraftServer): Landing? =
        server.getLevel(dimension)?.let { Landing(it, x, y, z, yaw, pitch) }

    companion object {

        /** Exactly where [player] is standing and looking, this moment. */
        fun of(player: ServerPlayer): Waypoint =
            Waypoint(
                player.level().dimension(),
                player.x,
                player.y,
                player.z,
                player.yRot,
                player.xRot,
            )
    }
}
