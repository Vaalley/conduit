package eu.mctraveler.importer

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PetOwnerRepairTest {
    @Test
    fun `finds real owner for an offline uuid`() {
        val real = UUID.fromString("c2c53835-9029-4941-904f-5f6b72082dba")

        assertEquals(real, PetOwnerRepair.realOwnerFor(OfflineUuid.of("Vaalley"), mapOf(real to "Vaalley")))
    }

    @Test
    fun `returns null for an unknown offline uuid`() {
        val real = UUID.fromString("c2c53835-9029-4941-904f-5f6b72082dba")

        assertNull(PetOwnerRepair.realOwnerFor(UUID.randomUUID(), mapOf(real to "Vaalley")))
    }

    @Test
    fun `does not map an offline uuid to itself`() {
        val offline = OfflineUuid.of("Vaalley")

        assertNull(PetOwnerRepair.realOwnerFor(offline, mapOf(offline to "Vaalley")))
    }
}
