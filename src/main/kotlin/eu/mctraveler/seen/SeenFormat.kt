package eu.mctraveler.seen

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object SeenFormat {
    private const val MINUTES_PER_HOUR = 60L
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
    private const val MINUTES_PER_MONTH = 30 * MINUTES_PER_DAY

    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)

    /**
     * [millis] as "2 months, 3 days, 4 hours, 5 minutes": a month is 30 days,
     * units that come to zero are left out, and a partial minute counts as a
     * whole one, so the answer is never "0 minutes".
     */
    fun elapsed(millis: Long): String {
        var minutes = (millis.coerceAtLeast(0) + 59_999) / 60_000
        if (minutes < 1) minutes = 1
        val parts = mutableListOf<String>()
        for ((size, unit) in listOf(MINUTES_PER_MONTH to "month", MINUTES_PER_DAY to "day", MINUTES_PER_HOUR to "hour", 1L to "minute")) {
            val count = minutes / size
            minutes %= size
            if (count > 0) parts += "$count $unit${if (count == 1L) "" else "s"}"
        }
        return parts.joinToString(", ")
    }

    /** [epochMillis] as a UTC calendar date, like "12 March 2026". */
    fun date(epochMillis: Long): String = dateFormat.format(Instant.ofEpochMilli(epochMillis))
}
