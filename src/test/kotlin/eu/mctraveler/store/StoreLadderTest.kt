package eu.mctraveler.store

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StoreLadderTest {
    @Test
    fun `ladder has the configured quantities and totals`() {
        assertArrayEquals(intArrayOf(1, 2, 4, 8, 10, 16, 32, 48, 64), StoreLadder.QUANTITIES)
        assertEquals(1728, StoreLadder.maxStock(3))
        assertEquals(3456, StoreLadder.maxStock(6))
        assertEquals(40L, StoreLadder.priceFor(10, 4))
    }

    @Test
    fun `buy cap respects wanted maximum and capacity`() {
        val record = StoreRecord(
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            "minecraft:overworld",
            net.minecraft.core.BlockPos.ZERO,
            com.google.gson.JsonParser.parseString("""{"id":"minecraft:diamond","count":1}"""),
            10,
            0,
            3,
            StoreKind.BUY,
            2_000,
        )
        assertEquals(1_728, record.buyCap)
        assertEquals(1_000, record.copy(wanted = 1_000).buyCap)
    }
}
