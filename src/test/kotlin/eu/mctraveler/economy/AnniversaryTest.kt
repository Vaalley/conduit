package eu.mctraveler.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnniversaryTest {
    @Test
    fun `payout rounds square root schedule`() {
        assertEquals(Economy.dollars(200), Anniversary.payout(1))
        assertEquals(Economy.dollars(283), Anniversary.payout(2))
        assertEquals(Economy.dollars(346), Anniversary.payout(3))
        assertEquals(Economy.dollars(400), Anniversary.payout(4))
    }

    @Test
    fun `due is empty when already current and catches up`() {
        val year = (365.25 * 24 * 60 * 60 * 1000).toLong()
        assertEquals(emptyList<Int>(), Anniversary.due(0, year * 2, 2))
        assertEquals(listOf(2, 3), Anniversary.due(0, year * 3, 1))
    }
}
