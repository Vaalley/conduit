package eu.mctraveler.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnniversaryTest {
    @Test
    fun `payout rounds square root schedule`() {
        assertEquals(200L, Anniversary.payout(1))
        assertEquals(283L, Anniversary.payout(2))
        assertEquals(346L, Anniversary.payout(3))
        assertEquals(400L, Anniversary.payout(4))
    }

    @Test
    fun `due is empty when already current and catches up`() {
        val year = (365.25 * 24 * 60 * 60 * 1000).toLong()
        assertEquals(emptyList<Int>(), Anniversary.due(0, year * 2, 2))
        assertEquals(listOf(2, 3), Anniversary.due(0, year * 3, 1))
    }
}
