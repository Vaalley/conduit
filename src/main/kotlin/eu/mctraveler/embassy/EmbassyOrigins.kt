package eu.mctraveler.embassy

import java.util.UUID
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/**
 * Where each player was standing before they entered the embassies dimension,
 * and the way back (spec stories 5-7; ADR 0003).
 *
 * Embassies is entered only by teleport and always left back to where the
 * player came from, so the origin is the whole of its position bookkeeping —
 * there is no Per-World Bucket and no Position Memory for a dimension outside
 * every trio. It is deliberately in memory only, as Nucleus's WeakHashMap was:
 * a server that goes down without warning loses only the return trip.
 *
 * Recorded by [beforeTeleport] on the way in and dropped on the way out;
 * consumed by a fall into the void, a disconnect, and the server stopping
 * (all in [EmbassiesFeature]).
 */
object EmbassyOrigins {

    /** A player's standing place in the moment before they entered embassies. */
    data class Origin(
        val dimension: ResourceKey<Level>,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
    )

    private val origins = HashMap<UUID, Origin>()

    /**
     * Called before every player teleport, from the one place that still knows
     * where the player is standing — Fabric's after-change-level event does
     * not carry the position they left.
     *
     * Entering embassies from anywhere else records the origin; leaving it
     * drops one (deviation 11: Nucleus meant to clear it here, and its
     * unreachable branch never did).
     */
    @JvmStatic
    fun beforeTeleport(player: ServerPlayer, destination: ResourceKey<Level>) {
        // A teleport of a removed player never happens, so it must not be
        // remembered as an arrival either.
        if (player.isRemoved) return
        val from = player.level().dimension()
        if (from == destination) return
        if (destination == EmbassiesFeature.DIMENSION) {
            origins[player.uuid] = Origin(from, player.x, player.y, player.z, player.yRot, player.xRot)
        } else if (from == EmbassiesFeature.DIMENSION) {
            origins.remove(player.uuid)
        }
    }

    /** Where [player] entered embassies from, or null if nothing was recorded. */
    fun originOf(player: ServerPlayer): Origin? = origins[player.uuid]

    /**
     * Puts [player] back where they entered from and forgets it. False when
     * there is nothing recorded — a player the server never saw arrive is left
     * exactly where they are (Nucleus's behaviour, and the only thing a crash
     * inside the dimension can leave behind).
     */
    fun sendHome(player: ServerPlayer): Boolean {
        val origin = origins.remove(player.uuid) ?: return false
        val level = player.level().server.getLevel(origin.dimension) ?: return false
        player.teleportTo(level, origin.x, origin.y, origin.z, emptySet(), origin.yaw, origin.pitch, false)
        // The drop into the void is not a fall the player may land from.
        player.resetFallDistance()
        return true
    }

    /** Forgets [uuid]'s origin — their session is over. */
    fun forget(uuid: UUID) {
        origins.remove(uuid)
    }
}
