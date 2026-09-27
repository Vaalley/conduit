package eu.mctraveler.economy

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NameCosmeticRefundTest {
    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    private fun entry(player: UUID, delta: Long, reason: String) = LedgerEntry(0L, player, delta, 0L, reason)

    @Test
    fun `every kind of name fee is paid back in full`() {
        val owed = NameCosmeticRefund.owed(
            sequenceOf(
                entry(alice, -10_000, "fee:cosmetic-tag"),
                entry(alice, -10_000, "fee:cosmetic-color"),
                entry(alice, -20_000, "fee:cosmetic-gradient"),
                entry(bob, -10_000, "fee:cosmetic-tag"),
            ),
        )
        assertEquals(mapOf(alice to 40_000L, bob to 10_000L), owed)
    }

    @Test
    fun `other ledger entries are ignored`() {
        val owed = NameCosmeticRefund.owed(
            sequenceOf(
                entry(alice, -2_000, "fee:store-create"),
                entry(alice, 10_000, "join-bonus"),
            ),
        )
        assertEquals(emptyMap<UUID, Long>(), owed)
    }

    @Test
    fun `a player already refunded is owed nothing more`() {
        val owed = NameCosmeticRefund.owed(
            sequenceOf(
                entry(alice, -10_000, "fee:cosmetic-tag"),
                entry(alice, 10_000, Reasons.REFUND_NAME_COSMETICS),
                entry(bob, -10_000, "fee:cosmetic-color"),
            ),
        )
        assertEquals(mapOf(bob to 10_000L), owed)
    }

    @Test
    fun `a purchase made after a partial refund is still paid back`() {
        val owed = NameCosmeticRefund.owed(
            sequenceOf(
                entry(alice, -10_000, "fee:cosmetic-tag"),
                entry(alice, 10_000, Reasons.REFUND_NAME_COSMETICS),
                entry(alice, -20_000, "fee:cosmetic-gradient"),
            ),
        )
        assertEquals(mapOf(alice to 20_000L), owed)
    }
}
