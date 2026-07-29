package eu.mctraveler.importer

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Admin status is vanilla operator status (spec User Story 41), so the
 * backends' op lists become the merged server's one real ops list — re-keyed
 * from the offline UUIDs an offline-mode server wrote to the Mojang UUIDs the
 * online-mode server checks.
 */
class OpsImportTest {

    private val notch = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private val identities = PlayerIdentities.resolve(supplied = emptyMap(), uuidCache = mapOf(notch to "Notch"))

    private fun backendOps(vararg names: String) = names.joinToString(",", "[", "]") { name ->
        """{"uuid":"${OfflineUuid.of(name)}","name":"$name","level":4,"bypassesPlayerLimit":false}"""
    }

    @Test
    fun `an operator keeps their operator status under their Mojang uuid`() {
        val ops = OpsImport.rekey(OpsImport.parse(backendOps("Notch")), identities)

        assertEquals(listOf(OpEntry(notch, "Notch", level = 4, bypassesPlayerLimit = false)), ops.entries)
        assertEquals(emptyList<String>(), ops.unresolved)
    }

    @Test
    fun `an operator listed on both backends is one operator`() {
        val entries = OpsImport.parse(backendOps("Notch")) + OpsImport.parse(backendOps("Notch"))

        val ops = OpsImport.rekey(entries, identities)

        assertEquals(1, ops.entries.size)
    }

    @Test
    fun `an aliased operator is re-keyed to the identity they play as`() {
        val ops = OpsImport.rekey(OpsImport.parse(backendOps("iElmo")), identities)

        assertEquals(
            listOf(OpEntry(UUID.fromString("be9482bb-6bcd-4df3-9cf4-9f1fb61c5e93"), "iElmo", 4, false)),
            ops.entries,
        )
    }

    @Test
    fun `an operator no identity can be found for is reported rather than dropped quietly`() {
        val ops = OpsImport.rekey(OpsImport.parse(backendOps("Stranger")), identities)

        assertEquals(emptyList<OpEntry>(), ops.entries)
        assertEquals(listOf("Stranger"), ops.unresolved)
    }

    @Test
    fun `a bypassesPlayerLimit operator keeps that too, at their own level`() {
        val backend = """[{"uuid":"${OfflineUuid.of("Notch")}","name":"Notch","level":2,"bypassesPlayerLimit":true}]"""

        val ops = OpsImport.rekey(OpsImport.parse(backend), identities)

        assertEquals(listOf(OpEntry(notch, "Notch", level = 2, bypassesPlayerLimit = true)), ops.entries)
    }

    @Test
    fun `the ops list is written in the format the server reads it back from`() {
        val serialized = OpsImport.serialize(listOf(OpEntry(notch, "Notch", 4, false)))

        assertEquals(
            """
            [
              {
                "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                "name": "Notch",
                "level": 4,
                "bypassesPlayerLimit": false
              }
            ]
            """.trimIndent(),
            serialized,
        )
    }
}
