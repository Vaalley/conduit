package eu.mctraveler.economy

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EconomyStatsTest {
    @Test
    fun `aggregates faucets sinks transfers and seven day window`() {
        val player = UUID.randomUUID()
        val window = 7L * 24 * 60 * 60 * 1000
        val entries = sequenceOf(
            LedgerEntry(0, player, 10_000, 10_000, "join-bonus"),
            LedgerEntry(window + 1, player, 2_000, 12_000, "stamp:first_steps"),
            LedgerEntry(window + 2, player, -3_000, 9_000, "admin:admin"),
            LedgerEntry(window + 3, player, -1_000, 8_000, "pay:other"),
            LedgerEntry(window + 4, player, 1_000, 9_000, "pay:other"),
        )
        val stats = EconomyStats.of(entries, window * 2)

        assertEquals(12_000L, stats.allTime.created)
        assertEquals(3_000L, stats.allTime.destroyed)
        assertEquals(9_000L, stats.allTime.net)
        assertEquals(1_000L, stats.allTime.transferVolume)
        assertEquals(2_000L, stats.lastSevenDays.created)
    }
}
