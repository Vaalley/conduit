package eu.mctraveler.worlds

import eu.mctraveler.text.Paint
import java.util.UUID
import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * The three-second wind-up every player-initiated teleport goes through: a
 * count on the action bar, a note-block pling per second, and the journey
 * itself when the count runs out — unless the player walks away first, which
 * cancels it.
 *
 * A player has at most one countdown; starting another replaces it. The
 * journey is a closure rather than a [Landing] because most destinations are
 * only worth resolving at the moment of departure — another player's position,
 * a bed that may have been broken in the meantime — and because some of them
 * spend energy, which must not happen for a countdown that was cancelled.
 */
object TeleportCountdown {

    const val SECONDS = 3
    const val TICKS_PER_SECOND = 20
    const val DURATION_TICKS = SECONDS * TICKS_PER_SECOND

    /**
     * How far a player may drift before the count is called off, squared. Half
     * a block: enough to absorb the server's own position jitter, not enough
     * to take a step.
     */
    const val MOVE_TOLERANCE_SQ = 0.25

    /** A count in progress, [go] being the journey it ends in. */
    private class Pending(
        val player: ServerPlayer,
        val startTick: Int,
        val dimension: ResourceKey<Level>,
        val origin: Vec3,
        val go: () -> Unit,
    )

    private val pending = LinkedHashMap<UUID, Pending>()

    /**
     * Starts [player]'s count, replacing any count already running for them.
     * [go] runs on the server thread [DURATION_TICKS] later if the player is
     * still online and has not moved.
     */
    fun begin(player: ServerPlayer, go: () -> Unit) {
        val now = player.level().server.tickCount
        pending[player.uuid] = Pending(player, now, player.level().dimension(), player.position(), go)
        announce(player, SECONDS)
    }

    fun isCounting(uuid: UUID): Boolean = uuid in pending

    /** Drops [uuid]'s count without a word, if there is one. */
    fun forget(uuid: UUID) {
        pending.remove(uuid)
    }

    /** Advances every count; runs from the end-of-tick hook. */
    fun tick(server: MinecraftServer) {
        if (pending.isEmpty()) return
        val now = server.tickCount
        for ((uuid, count) in pending.entries.toList()) {
            val player = count.player
            if (player.hasDisconnected() || player.isRemoved) {
                pending.remove(uuid)
                continue
            }
            if (hasMoved(count, player)) {
                pending.remove(uuid)
                player.sendSystemMessage(Paint.red("Teleport cancelled: you moved"), true)
                play(player, SoundEvents.NOTE_BLOCK_BASS, 0.5f)
                continue
            }
            val elapsed = now - count.startTick
            if (elapsed >= DURATION_TICKS) {
                pending.remove(uuid)
                player.sendSystemMessage(Component.empty(), true)
                play(player, SoundEvents.NOTE_BLOCK_PLING, 2.0f)
                count.go()
                continue
            }
            announcementAt(elapsed)?.let { announce(player, it) }
        }
    }

    fun clear() = pending.clear()

    /**
     * The number spoken at [elapsedTicks] into a count, or null when this tick
     * is between numbers. Tick 0 says [SECONDS]; [begin] speaks it itself, so
     * the tick loop only ever sees the later ones.
     */
    fun announcementAt(elapsedTicks: Int): Int? =
        if (elapsedTicks in 1 until DURATION_TICKS && elapsedTicks % TICKS_PER_SECOND == 0) {
            SECONDS - elapsedTicks / TICKS_PER_SECOND
        } else {
            null
        }

    /** Whether [current] has left the half-block circle around [origin]. */
    fun hasMoved(origin: Vec3, current: Vec3): Boolean = origin.distanceToSqr(current) > MOVE_TOLERANCE_SQ

    private fun hasMoved(count: Pending, player: ServerPlayer): Boolean =
        player.level().dimension() != count.dimension || hasMoved(count.origin, player.position())

    private fun announce(player: ServerPlayer, seconds: Int) {
        player.sendSystemMessage(
            Paint.aqua("Teleporting in ", Paint.bold(seconds), "..."),
            true,
        )
        play(player, SoundEvents.NOTE_BLOCK_PLING, 1.0f + (SECONDS - seconds) * 0.2f)
    }

    /** Plays [sound] for [player] alone, at their own position. */
    private fun play(player: ServerPlayer, sound: Holder<SoundEvent>, pitch: Float) {
        player.connection.send(
            ClientboundSoundPacket(
                sound,
                SoundSource.PLAYERS,
                player.x,
                player.y,
                player.z,
                1.0f,
                pitch,
                player.random.nextLong(),
            ),
        )
    }
}
