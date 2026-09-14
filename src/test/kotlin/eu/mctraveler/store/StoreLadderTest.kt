package eu.mctraveler.store

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StoreLadderTest {
    @Test
    fun `ladder has the configured quantities and totals`() {
        assertArrayEquals(intArrayOf(1, 2, 4, 8, 10, 16, 32, 48, 64), StoreLadder.QUANTITIES)
        assertEquals(1728, StoreLadder.MAX_STOCK)
        assertEquals(40L, StoreLadder.priceFor(10, 4))
    }
}
