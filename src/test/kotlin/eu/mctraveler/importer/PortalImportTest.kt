package eu.mctraveler.importer

import eu.mctraveler.persistence.JsonPlayerStore
import eu.mctraveler.persistence.NameCache
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.SharedConstants
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The cutover itself: a Portal deployment on disk becomes a server run
 * directory ready to boot (spec User Stories 43–44).
 */
class PortalImportTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @TempDir
    lateinit var dir: Path

    private val wanderer = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val notch = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    private lateinit var portal: PortalDeploymentFixture
    private val target: Path get() = dir.resolve("run")

    private val regionsFile = """
        {
          "regions": {
            "0": {
              "title": "Wanderer's Keep",
              "start-x": -10,
              "start-z": -10,
              "end-x": 10,
              "end-z": 10,
              "world": "last_nether",
              "members": [
                "11111111-2222-4333-8444-555555555555"
              ]
            }
          }
        }
    """.trimIndent()

    @BeforeEach
    fun buildDeployment() {
        portal = PortalDeploymentFixture(dir).build()
        portal.uuidCache(mapOf(wanderer to "Wanderer", notch to "Notch"))
        portal.ops("Notch")
        portal.regions(regionsFile)
        portal.portalPlayer(
            wanderer,
            """{"lastServer":"secondary","balance":1234.50,"notepad":["page one"],"isAdmin":true}""",
        )
        // Wanderer was last in Secondary; Primary still remembers where they stood.
        portal.playerdata(
            "primary",
            "Wanderer",
            dimension = "minecraft:the_nether",
            pos = Triple(10.5, 70.0, -20.5),
            rotation = 30f to -5f,
            respawn = Triple(100, 64, 200),
        )
        portal.playerdata(
            "secondary",
            "Wanderer",
            pos = Triple(500.5, 71.0, 600.5),
        ) { putInt("XpLevel", 42) }
        portal.advancements("secondary", "Wanderer", """{"minecraft:story/mine_stone":{"done":true}}""")
        portal.advancements("primary", "Wanderer", """{"minecraft:story/root":{"done":true}}""")
        portal.stats("secondary", "Wanderer", """{"stats":{}}""")
        // Notch only ever played on Primary.
        portal.playerdata("primary", "Notch")
        portal.chunks("primary", "")
        portal.chunks("primary", "DIM-1")
        portal.chunks("secondary", "")
        portal.chunks("secondary", "DIM-1")
        portal.chunks("secondary", "DIM1")
    }

    private fun migrate(plan: ImportPlan = portal.plan(target)) = PortalImport(plan).run()

    private fun players() = JsonPlayerStore(target.resolve("mctraveler/players"))

    private fun playerdata(uuid: UUID) =
        NbtIo.readCompressed(target.resolve("world/playerdata/$uuid.dat"), NbtAccounter.unlimitedHeap())

    @Test
    fun `both backend worlds arrive as the Primary and Secondary trios`() {
        migrate()

        assertEquals("chunk bytes of primary/", Files.readString(target.resolve("world/region/r.0.0.mca")))
        assertEquals("chunk bytes of primary/DIM-1", Files.readString(target.resolve("world/DIM-1/region/r.0.0.mca")))
        assertEquals(
            "chunk bytes of secondary/",
            Files.readString(target.resolve("world/dimensions/mctraveler/secondary/region/r.0.0.mca")),
        )
        assertEquals(
            "chunk bytes of secondary/DIM-1",
            Files.readString(target.resolve("world/dimensions/mctraveler/secondary_nether/region/r.0.0.mca")),
        )
        assertEquals(
            "chunk bytes of secondary/DIM1",
            Files.readString(target.resolve("world/dimensions/mctraveler/secondary_end/region/r.0.0.mca")),
        )
    }

    @Test
    fun `an aliased player's save is re-keyed to the identity they play as`() {
        portal.playerdata("primary", "travelcraft2012")

        migrate()

        val aliased = UUID.fromString("461789c5-4501-48a0-b47d-7574c9a7b9ec")
        assertEquals("minecraft:overworld", playerdata(aliased).getStringOr("Dimension", ""))
        assertEquals("primary", players().lastWorld(aliased))
    }

    @Test
    fun `the level is handed to vanilla at the version the backend left it`() {
        migrate()

        val level = NbtIo.readCompressed(target.resolve("world/level.dat"), NbtAccounter.unlimitedHeap())
        assertEquals(4536, level.getIntOr("DataVersion", 0))
        assertFalse(Files.exists(target.resolve("world/session.lock")), "the backend's lock must not travel")
    }

    @Test
    fun `a player's live save is the one from the World they were last in`() {
        migrate()

        val live = playerdata(wanderer)
        assertEquals(42, live.getIntOr("XpLevel", 0))
        assertEquals("mctraveler:secondary", live.getStringOr("Dimension", ""))
        assertEquals(500.5, live.getListOrEmpty("Pos").getDoubleOr(0, 0.0))
    }

    @Test
    fun `the other World's save becomes that World's Per-World Bucket`() {
        migrate()

        val bucket = checkNotNull(players().bucket(wanderer, "primary"))
        assertEquals("nether", bucket.dimension)
        assertEquals(10.5, bucket.x)
        assertEquals(-20.5, bucket.z)
        assertEquals(30f, bucket.yaw)
        assertEquals(-5f, bucket.pitch)
    }

    @Test
    fun `a bed in the other World comes with the bucket`() {
        migrate()

        val respawn = checkNotNull(players().bucket(wanderer, "primary")?.respawn)
        assertEquals("nether", respawn.dimension)
        assertEquals(100, respawn.x)
        assertEquals(200, respawn.z)
    }

    @Test
    fun `the World a player is already in needs no bucket`() {
        migrate()

        assertNull(players().bucket(wanderer, "secondary"))
    }

    @Test
    fun `the Portal's player record arrives with its legacy fields untouched`() {
        migrate()

        val record = Files.readString(target.resolve("mctraveler/players/$wanderer.json"))
        assertTrue(record.contains("\"balance\":1234.50"), "legacy balance was rewritten: $record")
        assertTrue(record.contains("\"isAdmin\":true"), "the Portal's admin flag was dropped: $record")
        assertEquals(listOf("page one"), players().notepadPages(wanderer))
    }

    @Test
    fun `lastServer becomes the player's last World`() {
        migrate()

        assertEquals("secondary", players().lastWorld(wanderer))
        assertEquals("primary", players().lastWorld(notch))
    }

    @Test
    fun `the uuid cache seeds the name cache`() {
        migrate()

        val names = NameCache(target.resolve("mctraveler/uuid-cache.json"))
        assertEquals("Wanderer", names.usernameFor(wanderer))
        assertEquals("Notch", names.usernameFor(notch))
    }

    @Test
    fun `operators keep their status under their Mojang uuid`() {
        migrate()

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
            Files.readString(target.resolve("ops.json")),
        )
    }

    @Test
    fun `regions arrive in the file the live region service keeps`() {
        migrate()

        assertEquals(regionsFile, Files.readString(target.resolve("regions.json")))
    }

    @Test
    fun `advancements and statistics follow the live save`() {
        migrate()

        assertEquals(
            """{"minecraft:story/mine_stone":{"done":true}}""",
            Files.readString(target.resolve("world/advancements/$wanderer.json")),
        )
        assertTrue(Files.exists(target.resolve("world/stats/$wanderer.json")))
    }

    @Test
    fun `re-running against a migrated save is refused`() {
        val report = migrate()

        val error = assertThrows(IllegalStateException::class.java) { migrate() }

        assertTrue(
            error.message!!.startsWith("$target has already been migrated"),
            "unhelpful refusal: ${error.message}",
        )
        // And the migrated save is exactly as the first run left it.
        assertEquals(2, report.playersMigrated)
        assertEquals("mctraveler:secondary", playerdata(wanderer).getStringOr("Dimension", ""))
    }

    @Test
    fun `migrating into a directory that already holds a world is refused`() {
        Files.createDirectories(target.resolve("world"))

        val error = assertThrows(IllegalStateException::class.java) { migrate() }

        assertEquals(
            "$target already contains \"world\" — migrate into a fresh server run directory",
            error.message,
        )
    }

    @Test
    fun `a save nobody can be identified from stops the migration before anything is written`() {
        portal.playerdata("primary", "Stranger")
        portal.userCache("Stranger")

        val error = assertThrows(IllegalStateException::class.java) { migrate() }

        assertTrue(error.message!!.contains("Stranger"), "the unknown player was not named: ${error.message}")
        assertFalse(Files.exists(target.resolve("world")), "a refused migration must write nothing")
    }

    @Test
    fun `unidentified saves can be left behind deliberately`() {
        portal.playerdata("primary", "Stranger")
        portal.userCache("Stranger")

        val report = migrate(portal.plan(target).copy(skipUnidentified = true))

        assertEquals(listOf("Stranger (${OfflineUuid.of("Stranger")}, primary)"), report.unidentifiedSaves)
        assertTrue(Files.exists(target.resolve("world")))
    }
}
