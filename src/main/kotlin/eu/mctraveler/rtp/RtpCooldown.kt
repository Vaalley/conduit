package eu.mctraveler.rtp

import java.util.UUID

/**
 * When each player may next `/rtp`, in server ticks. In memory only: a
 * restart forgives everyone, which for a five-minute wait is the right trade
 * against another file to keep.
 */
enum class RtpKind {
    OVERWORLD,
    END,
}

object RtpCooldown {

    private val readyAt = HashMap<Pair<UUID, RtpKind>, Int>()

    /** Ticks [uuid] still has to wait at [now], or 0 when they may go. */
    fun remaining(uuid: UUID, now: Int, kind: RtpKind = RtpKind.OVERWORLD): Int =
        ((readyAt[uuid to kind] ?: 0) - now).coerceAtLeast(0)

    fun start(uuid: UUID, now: Int, durationTicks: Int, kind: RtpKind = RtpKind.OVERWORLD) {
        readyAt[uuid to kind] = now + durationTicks
    }

    fun forget(uuid: UUID, kind: RtpKind = RtpKind.OVERWORLD) {
        readyAt.remove(uuid to kind)
    }

    fun clear() = readyAt.clear()

    /** `4m 12s`, `12s`, or `5m` — never `0m 12s`, never `4m 0s`. */
    fun format(ticks: Int): String {
        val seconds = (ticks + 19) / 20
        val minutes = seconds / 60
        val rest = seconds % 60
        return when {
            minutes == 0 -> "${rest}s"
            rest == 0 -> "${minutes}m"
            else -> "${minutes}m ${rest}s"
        }
    }
}
