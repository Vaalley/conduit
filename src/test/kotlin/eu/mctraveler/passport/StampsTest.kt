package eu.mctraveler.passport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StampsTest {
    @Test
    fun `stamp tiers have the specified counts and bounty total`() {
        assertEquals(6, Stamps.ALL.count { it.tier == StampTier.COMMON })
        assertEquals(11, Stamps.ALL.count { it.tier == StampTier.UNCOMMON })
        assertEquals(16, Stamps.ALL.count { it.tier == StampTier.RARE })
        assertEquals(5, Stamps.ALL.count { it.tier == StampTier.EPIC })
        assertEquals(158_000L, Stamps.totalBounty())
    }

    @Test
    fun `bountyFor ignores unknown stamp ids`() {
        assertEquals(
            StampTier.COMMON.bounty + StampTier.EPIC.bounty,
            Stamps.bountyFor(listOf("first_steps", "globetrotter", "unknown")),
        )
    }
}
