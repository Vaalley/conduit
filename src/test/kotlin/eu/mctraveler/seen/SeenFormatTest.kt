package eu.mctraveler.seen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SeenFormatTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `a partial minute rounds up to a whole one`() {
        assertEquals("1 minute", SeenFormat.elapsed(1))
        assertEquals("1 minute", SeenFormat.elapsed(59_999))
        assertEquals("2 minutes", SeenFormat.elapsed(minute + 1))
    }

    @Test
    fun `nothing elapsed still reads as one minute`() {
        assertEquals("1 minute", SeenFormat.elapsed(0))
        assertEquals("1 minute", SeenFormat.elapsed(-5_000))
    }

    @Test
    fun `units that come to zero are left out`() {
        assertEquals("1 hour", SeenFormat.elapsed(hour))
        assertEquals("3 days, 5 minutes", SeenFormat.elapsed(3 * day + 5 * minute))
    }

    @Test
    fun `months are thirty days and everything breaks down`() {
        assertEquals(
            "2 months, 3 days, 4 hours, 5 minutes",
            SeenFormat.elapsed(2 * 30 * day + 3 * day + 4 * hour + 5 * minute),
        )
        assertEquals("1 month", SeenFormat.elapsed(30 * day))
        assertEquals("29 days, 23 hours, 59 minutes", SeenFormat.elapsed(30 * day - minute))
    }

    @Test
    fun `seconds past an exact hour round the minutes up`() {
        assertEquals("2 hours, 6 minutes", SeenFormat.elapsed(2 * hour + 5 * minute + 1_000))
    }

    @Test
    fun `dates are UTC calendar dates`() {
        assertEquals("1 January 1970", SeenFormat.date(0))
        assertEquals("12 March 2026", SeenFormat.date(1_773_273_600_000))
    }
}
