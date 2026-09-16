package eu.mctraveler.economy

import kotlin.math.roundToLong
import kotlin.math.sqrt

object Anniversary {
    private const val YEAR_MILLIS = 365.25 * 24 * 60 * 60 * 1000

    fun years(firstJoin: Long, now: Long): Int =
        if (now <= firstJoin) 0 else ((now - firstJoin) / YEAR_MILLIS).toInt()

    fun payout(year: Int): Long {
        require(year >= 1) { "anniversary year must be positive" }
        return (200.0 * sqrt(year.toDouble())).roundToLong()
    }

    fun due(firstJoin: Long, now: Long, alreadyPaid: Int): List<Int> =
        ((alreadyPaid + 1)..years(firstJoin, now)).toList()
}
