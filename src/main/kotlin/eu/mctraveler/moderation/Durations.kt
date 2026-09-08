package eu.mctraveler.moderation

import java.util.Locale

object Durations {
    fun parse(value: String): Long? {
        return when (val input = value.lowercase(Locale.ROOT)) {
            "perm", "permanent" -> null
            else -> {
                val match = Regex("""(\d+)([smhdw])""").matchEntire(input)
                    ?: throw IllegalArgumentException("Invalid duration: $value")
                val amount = match.groupValues[1].toLong()
                val multiplier = when (match.groupValues[2]) {
                    "s" -> 1_000L
                    "m" -> 60_000L
                    "h" -> 3_600_000L
                    "d" -> 86_400_000L
                    "w" -> 604_800_000L
                    else -> error("unreachable")
                }
                Math.multiplyExact(amount, multiplier)
            }
        }
    }
}
