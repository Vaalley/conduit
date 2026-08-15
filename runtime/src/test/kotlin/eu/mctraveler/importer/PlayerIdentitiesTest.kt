package eu.mctraveler.importer

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The identity index the whole migration hangs on: every legacy file the
 * backends wrote is keyed by an offline UUID, and the merged server keys
 * everything by the Mojang UUID the Portal already used for its own player
 * records.
 */
class PlayerIdentitiesTest {

    private val notch = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private val notchOffline = UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f")

    @Test
    fun `a cached player is found by the offline uuid their playerdata is keyed by`() {
        val identities = PlayerIdentities.resolve(supplied = emptyMap(), uuidCache = mapOf(notch to "Notch"))

        val identity = checkNotNull(identities[notchOffline])
        assertEquals("Notch", identity.name)
        assertEquals(notch, identity.uuid)
    }

    @Test
    fun `an offline uuid nobody claims resolves to nothing`() {
        val identities = PlayerIdentities.resolve(supplied = emptyMap(), uuidCache = emptyMap())

        assertNull(identities[notchOffline])
    }

    @Test
    fun `the aliased identities resolve from the remap table alone`() {
        val identities = PlayerIdentities.resolve(supplied = emptyMap(), uuidCache = emptyMap())

        val travelcraft = checkNotNull(identities[UUID.fromString("68f1f749-8b9f-3727-863a-b14eea1c5c83")])
        assertEquals(UUID.fromString("461789c5-4501-48a0-b47d-7574c9a7b9ec"), travelcraft.uuid)
        val ielmo = checkNotNull(identities[UUID.fromString("9cd79c13-e70d-3a45-87cb-c7b2b08e8787")])
        assertEquals(UUID.fromString("be9482bb-6bcd-4df3-9cf4-9f1fb61c5e93"), ielmo.uuid)
    }

    @Test
    fun `the remap table outranks a cache entry for the same aliased name`() {
        val impostor = UUID.fromString("11111111-1111-4111-8111-111111111111")

        val identities = PlayerIdentities.resolve(
            supplied = mapOf("travelcraft2012" to impostor),
            uuidCache = mapOf(impostor to "travelcraft2012"),
        )

        val travelcraft = checkNotNull(identities.byName("travelcraft2012"))
        assertEquals(UUID.fromString("461789c5-4501-48a0-b47d-7574c9a7b9ec"), travelcraft.uuid)
    }

    @Test
    fun `an operator-supplied identity outranks the Portal's cache`() {
        val stale = UUID.fromString("22222222-2222-4222-8222-222222222222")

        val identities = PlayerIdentities.resolve(
            supplied = mapOf("Notch" to notch),
            uuidCache = mapOf(stale to "Notch"),
        )

        assertEquals(notch, checkNotNull(identities.byName("Notch")).uuid)
    }

    @Test
    fun `two names claiming one Mojang uuid is refused rather than guessed at`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PlayerIdentities.resolve(
                supplied = mapOf("Notch" to notch),
                uuidCache = mapOf(notch to "Notch_old"),
            )
        }

        assertEquals(
            "\"Notch\" and \"Notch_old\" both claim uuid $notch — resolve the conflict in the identities file",
            error.message,
        )
    }
}
