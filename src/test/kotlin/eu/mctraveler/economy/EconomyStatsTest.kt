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
            LedgerEntry(0, player, 100, 100, "join-bonus"),
            LedgerEntry(window + 1, player, 20, 120, "stamp:first_steps"),
            LedgerEntry(window + 2, player, -30, 90, "admin:admin"),
            LedgerEntry(window + 3, player, -10, 80, "pay:other"),
            LedgerEntry(window + 4, player, 10, 90, "pay:other"),
        )
        val stats = EconomyStats.of(entries, window * 2)

        assertEquals(120L, stats.allTime.created)
        assertEquals(30L, stats.allTime.destroyed)
        assertEquals(90L, stats.allTime.net)
        assertEquals(10L, stats.allTime.transferVolume)
        assertEquals(20L, stats.lastSevenDays.created)
    }
}
