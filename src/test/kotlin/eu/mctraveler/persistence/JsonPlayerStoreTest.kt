package eu.mctraveler.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Unit tier for the Persistence service's player store: behaviour is asserted
 * through the [PlayerStore] interface; schema compatibility with the Portal's
 * `players/<uuid>.json` files is asserted against raw file content, because the
 * on-disk format is itself a public contract (the importer and any legacy data
 * on disk depend on it).
 */
class JsonPlayerStoreTest {
    @TempDir
    lateinit var dir: Path

    private val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    private fun store(): PlayerStore = JsonPlayerStore(dir)

    @Test
    fun `a player never seen before has no last World`() {
        assertNull(store().lastWorld(uuid))
    }

    @Test
    fun `last World round-trips through the store`() {
        store().setLastWorld(uuid, "secondary")
        assertEquals("secondary", store().lastWorld(uuid))
    }

    @Test
    fun `a player with no saved notepad has none`() {
        assertNull(store().notepadPages(uuid))
    }

    @Test
    fun `notepad pages round-trip through the store`() {
        store().setNotepadPages(uuid, listOf("Welcome to MCTraveler!", "page two"))
        assertEquals(listOf("Welcome to MCTraveler!", "page two"), store().notepadPages(uuid))
    }

    @Test
    fun `notepad text with quotes, newlines and unicode round-trips`() {
        val page = "She said \"hi\"\nback\\slash é世😀"
        store().setNotepadPages(uuid, listOf(page))
        assertEquals(listOf(page), store().notepadPages(uuid))
    }

    // A Portal-written player file, legacy fields and all: timestamps/ipAddress
    // (dead tracking data), balance/geoLocation/balanceBeheadingLoss
    // (predecessor-server economy data), isAdmin (the Portal's admin flag —
    // admin status is vanilla operator status now, so the field is legacy
    // data). Compact JSON, as the Portal's JSON.stringify wrote it — except
    // balance/balanceBeheadingLoss keep predecessor-era trailing zeros that a
    // parse-and-reserialize would normalize away.
    private val portalFile =
        """{"timestamps":{"login":1638316800000,"logout":1638320400000,"firstSeen":1370044800000},""" +
            """"ipAddress":"203.0.113.7","lastServer":"secondary","balance":1234.50,""" +
            """"geoLocation":{"country":"SE","city":"Stockholm"},"balanceBeheadingLoss":66.60,""" +
            """"notepad":["old page"],"isAdmin":true}"""

    private fun seedPortalFile(): Path {
        val file = dir.resolve("$uuid.json")
        Files.writeString(file, portalFile)
        return file
    }

    @Test
    fun `reads the live fields of a Portal-written player file`() {
        seedPortalFile()
        val store = store()
        assertEquals("secondary", store.lastWorld(uuid))
        assertEquals(listOf("old page"), store.notepadPages(uuid))
    }

    @Test
    fun `legacy balance reads as an integer and rewrites without losing other fields`() {
        val file = seedPortalFile()
        val store = store()
        assertEquals(1234L, store.balance(uuid))
        store.setBalance(uuid, 40)
        assertEquals(40L, store.balance(uuid))
        assertEquals(
            portalFile.replace("\"balance\":1234.50", "\"balance\":40"),
            Files.readString(file),
        )
    }

    @Test
    fun `reads first seen timestamp from legacy timestamps`() {
        seedPortalFile()
        assertEquals(1370044800000L, store().firstJoin(uuid))
    }

    @Test
    fun `modifying a live field preserves every other field byte-for-byte`() {
        val file = seedPortalFile()
        store().setLastWorld(uuid, "primary")
        // Identical bytes except the lastServer value, in place — key order,
        // number formatting (1234.50, 66.60), nested objects, and the legacy
        // isAdmin flag all intact.
        assertEquals(
            portalFile.replace(""""lastServer":"secondary"""", """"lastServer":"primary""""),
            Files.readString(file),
        )
    }

    @Test
    fun `a field this mod never saw is appended without disturbing the rest`() {
        val file = dir.resolve("$uuid.json")
        Files.writeString(file, """{"balance":9000.01,"futureField":{"a":[1,2.20,"x"]}}""")
        store().setLastWorld(uuid, "primary")
        assertEquals(
            """{"balance":9000.01,"futureField":{"a":[1,2.20,"x"]},"lastServer":"primary"}""",
            Files.readString(file),
        )
    }

    @Test
    fun `legacy string values keep their original escapes byte-for-byte`() {
        val file = dir.resolve("$uuid.json")
        // Escaped quote, a brace inside the string, a backslash, and a \u
        // escape a tree parser would normalize to a plain character.
        Files.writeString(
            file,
            """{"legacyNote":"a \" } b \\ caf\u00e9","lastServer":"primary"}""",
        )
        store().setLastWorld(uuid, "secondary")
        assertEquals(
            """{"legacyNote":"a \" } b \\ caf\u00e9","lastServer":"secondary"}""",
            Files.readString(file),
        )
    }

    @Test
    fun `the retired Per-World Bucket field is now legacy data that passes through untouched`() {
        // The store modelled `worlds` until the merge retired the Worlds; the
        // field did not go anywhere, so every migrated record on this server
        // still carries one and the store must now leave it exactly alone. The
        // merge tool reads and rewrites it through
        // `eu.mctraveler.importer.PerWorldBuckets` instead.
        val file = dir.resolve("$uuid.json")
        val record =
            """{"balance":1234.50,"worlds":{"secondary":{"dimension":"end",""" +
                """"x":1.0,"y":2.0,"z":3.0,"yaw":0.0,"pitch":0.0,"futureRespawn":{"x":1}}},""" +
                """"lastServer":"secondary"}"""
        Files.writeString(file, record)
        store().setLastWorld(uuid, "primary")
        assertEquals(
            record.replace(""""lastServer":"secondary"""", """"lastServer":"primary""""),
            Files.readString(file),
        )
    }

    @Test
    fun `a player with no crystal state has neither energy nor a recharge threshold`() {
        assertNull(store().crystalEnergy(uuid))
        assertNull(store().crystalNextRegenAt(uuid))
    }

    @Test
    fun `crystal energy and recharge threshold round-trip through the store`() {
        val store = store()
        store.setCrystalEnergy(uuid, 1)
        store.setCrystalNextRegenAt(uuid, 123456)
        assertEquals(1, store().crystalEnergy(uuid))
        assertEquals(123456, store().crystalNextRegenAt(uuid))
    }

    @Test
    fun `clearing the recharge threshold removes the field, leaving energy alone`() {
        val file = dir.resolve("$uuid.json")
        val store = store()
        store.setCrystalEnergy(uuid, 2)
        store.setCrystalNextRegenAt(uuid, 999)
        store.setCrystalNextRegenAt(uuid, null)
        assertNull(store().crystalNextRegenAt(uuid))
        assertEquals(2, store().crystalEnergy(uuid))
        assertEquals("""{"crystalEnergy":2}""", Files.readString(file))
    }

    @Test
    fun `crystal fields are written under their documented names beside legacy data`() {
        val file = seedPortalFile()
        val store = store()
        store.setCrystalEnergy(uuid, 0)
        store.setCrystalNextRegenAt(uuid, 24000)
        assertEquals(
            portalFile.dropLast(1) + ""","crystalEnergy":0,"crystalNextRegenAt":24000}""",
            Files.readString(file),
        )
    }

    @Test
    fun `a file that cannot be parsed is never overwritten`() {
        val file = dir.resolve("$uuid.json")
        Files.writeString(file, """{"balance":12.3""") // truncated write, e.g. a past crash
        assertThrows(IllegalArgumentException::class.java) { store().setLastWorld(uuid, "primary") }
        assertThrows(IllegalArgumentException::class.java) { store().lastWorld(uuid) }
        assertEquals("""{"balance":12.3""", Files.readString(file))
    }

    @Test
    fun `a file with duplicate keys is rejected, not silently collapsed`() {
        val file = dir.resolve("$uuid.json")
        Files.writeString(file, """{"balance":1,"balance":2}""")
        assertThrows(IllegalArgumentException::class.java) { store().setLastWorld(uuid, "primary") }
        assertEquals("""{"balance":1,"balance":2}""", Files.readString(file))
    }

    @Test
    fun `all balances scans valid json files and skips invalid files`() {
        val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
        Files.writeString(dir.resolve("$uuid.json"), """{"balance":12.5}""")
        Files.writeString(dir.resolve("$other.json"), """{"balance":30}""")
        Files.writeString(dir.resolve("not-a-uuid.json"), """{"balance":50}""")
        Files.writeString(dir.resolve("notes.txt"), """{"balance":60}""")
        Files.writeString(dir.resolve("00000000-0000-0000-0000-000000000003.json"), """{"balance":""")

        assertEquals(mapOf(uuid to 12L, other to 30L), store().allBalances())
    }

    @Test
    fun `name cosmetics round-trip and preserve legacy fields`() {
        val file = dir.resolve("$uuid.json")
        Files.writeString(file, """{"balance":12.50,"legacy":true}""")
        val store = store()
        store.setNameTag(uuid, "🐢")
        store.setNameColor(uuid, "FF8800")
        store.setNameGradient(uuid, "#112233", "#AABBCC")

        assertEquals("🐢", store.nameTag(uuid))
        assertEquals("#ff8800", store.nameColor(uuid))
        assertEquals("#112233" to "#aabbcc", store.nameGradient(uuid))
        assertEquals(
            """{"balance":12.50,"legacy":true,"nameTag":"🐢","nameColor":"#ff8800","nameGradientFrom":"#112233","nameGradientTo":"#aabbcc"}""",
            Files.readString(file),
        )
    }

    @Test
    fun `clearing name cosmetics removes only their fields`() {
        val file = dir.resolve("$uuid.json")
        val store = store()
        store.setNameTag(uuid, "★")
        store.setNameColor(uuid, "#FF8800")
        store.setNameGradient(uuid, "#112233", "#AABBCC")
        store.setNameTag(uuid, null)
        store.setNameColor(uuid, null)
        store.setNameGradient(uuid, null, null)

        assertNull(store.nameTag(uuid))
        assertNull(store.nameColor(uuid))
        assertNull(store.nameGradient(uuid))
        assertEquals("{}", Files.readString(file))
    }
}
