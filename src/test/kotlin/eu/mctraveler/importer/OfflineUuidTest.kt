package eu.mctraveler.importer

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The offline-UUID scheme the Portal's backends keyed everything by. The
 * expected values below were computed independently (`md5("OfflinePlayer:<name>")`
 * with the UUIDv3 variant/version bits applied by hand), not by this code.
 */
class OfflineUuidTest {
    @Test
    fun `an offline uuid is the version-3 md5 of the OfflinePlayer name`() {
        assertEquals(
            UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"),
            OfflineUuid.of("Notch"),
        )
    }

    @Test
    fun `the aliased identities' offline uuids come from their aliased names`() {
        assertEquals(
            UUID.fromString("68f1f749-8b9f-3727-863a-b14eea1c5c83"),
            OfflineUuid.of("travelcraft2012"),
        )
        assertEquals(
            UUID.fromString("9cd79c13-e70d-3a45-87cb-c7b2b08e8787"),
            OfflineUuid.of("iElmo"),
        )
    }

    @Test
    fun `offline uuids are case-sensitive, as the backends computed them`() {
        assertNotEquals(OfflineUuid.of("Notch"), OfflineUuid.of("notch"))
    }
}
