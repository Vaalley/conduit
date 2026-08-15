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

    /**
     * One migrated player's record. The Per-World Bucket is legacy data the
     * live store no longer models, so it is read back the way the merge tool
     * reads it — see [PerWorldBuckets].
     */
    private fun record(uuid: UUID) = target.resolve("mctraveler/players/$uuid.json")

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
    fun `a save already keyed by a Mojang uuid is carried across as itself`() {
        // Production reality: the live backends held thousands of version-4-keyed saves
        // beside the offline-keyed ones. They need no re-keying — and must not be
        // mistaken for offline uuids nobody can name, which would abandon them.
        val stranger = UUID.fromString("9e1cb3a6-4c2f-4a41-8f2e-51d0b0a4c7e3")
        portal.mojangKeyedPlayerdata("primary", stranger, Triple(77.5, 71.0, -12.5))

        val report = migrate()

        assertTrue(report.unidentifiedSaves.none { stranger.toString() in it }, "should not be reported as unknown")
        val save = playerdata(stranger)
        assertEquals(77.5, save.getList("Pos").get().getDouble(0).get(), "the inventory-bearing save came across")
        assertTrue(
            Files.exists(target.resolve("world/advancements/$stranger.json")),
            "advancements beside a Mojang-keyed save use the same uuid",
        )
    }

    @Test
    fun `moving the worlds lands the same save and leaves the backend levels emptied`() {
        val plan = portal.plan(target).copy(worldTransfer = WorldTransfer.MOVE)
        val primaryRegion = plan.primaryServerDir.resolve("world/region/r.0.0.mca")
        val secondaryRegion = plan.secondaryServerDir.resolve("last/region/r.0.0.mca")
        assertTrue(Files.exists(primaryRegion), "the fixture should start with Primary chunk data")

        migrate(plan)

        // Same destination as a copy — this is the space-free path to the same save.
        assertEquals("chunk bytes of primary/", Files.readString(target.resolve("world/region/r.0.0.mca")))
        assertEquals(
            "chunk bytes of secondary/",
            Files.readString(target.resolve("world/dimensions/mctraveler/secondary/region/r.0.0.mca")),
        )
        // ...and the chunk data is gone from the Portal, which is the trade being made.
        assertFalse(Files.exists(primaryRegion), "Primary's chunk data should have moved, not copied")
        assertFalse(Files.exists(secondaryRegion), "Secondary's chunk data should have moved, not copied")
    }

    @Test
    fun `one player with both an offline and a Mojang keyed save migrates once`() {
        // Someone who played before and after the backends went offline-mode owns two
        // files in one World. They are one person, and the newer file is where they
        // left off — the live cutover hit this as two saves racing for one destination.
        val old = portal.plan(target).primaryServerDir.resolve("world/playerdata/${OfflineUuid.of("Wanderer")}.dat")
        portal.mojangKeyedPlayerdata("primary", wanderer, Triple(999.5, 80.0, 999.5))
        val newer = portal.plan(target).primaryServerDir.resolve("world/playerdata/$wanderer.dat")
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(1_000_000))
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2_000_000))

        val report = migrate()

        assertEquals(2, report.playersMigrated, "Wanderer and Notch — Wanderer counted once, not twice")
        // Wanderer's Portal record says Secondary, so Secondary is still live; the
        // Primary pair collapses to the newer file for the bucket.
        assertEquals(500.5, playerdata(wanderer).getList("Pos").get().getDouble(0).get())
        assertEquals(
            999.5,
            PerWorldBuckets.of(record(wanderer), "primary")!!.x,
            "the newer Primary save seeds the bucket",
        )
    }

    @Test
    fun `a playerdata file that is not named after a uuid is ignored, not fatal`() {
        // The live deployment held 93 files named `<uuid>-<digits>.dat`. Whatever wrote
        // them, they key to nobody — and a cutover must not be stopped by strays the
        // operator cannot act on.
        val stray = portal.plan(target).primaryServerDir
            .resolve("world/playerdata/1cf923d4-18fa-4b80-a0bc-38f248831894-1115360035497270329.dat")
        Files.createDirectories(stray.parent)
        Files.writeString(stray, "not a save this server can key to anybody")

        val report = migrate()

        assertEquals(1, report.unnamedFiles.size, "the stray should be reported, once")
        assertTrue(report.unnamedFiles.single().startsWith("1cf923d4"), "reported by name")
        assertTrue(Files.exists(stray), "a file we cannot key is left exactly where it was")
    }

    @Test
    fun `a copy migration leaves the Portal's worlds untouched`() {
        val primaryRegion = portal.plan(target).primaryServerDir.resolve("world/region/r.0.0.mca")

        migrate()

        assertTrue(Files.exists(primaryRegion), "a copy must not disturb the Portal's levels")
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

        val bucket = checkNotNull(PerWorldBuckets.of(record(wanderer), "primary"))
        assertEquals("nether", bucket.dimension)
        assertEquals(10.5, bucket.x)
        assertEquals(-20.5, bucket.z)
        assertEquals(30f, bucket.yaw)
        assertEquals(-5f, bucket.pitch)
    }

    @Test
    fun `a bed in the other World comes with the bucket`() {
        migrate()

        val respawn = checkNotNull(PerWorldBuckets.of(record(wanderer), "primary")?.respawn)
        assertEquals("nether", respawn.dimension)
        assertEquals(100, respawn.x)
        assertEquals(200, respawn.z)
    }

    @Test
    fun `the World a player is already in needs no bucket`() {
        migrate()

        assertNull(PerWorldBuckets.of(record(wanderer), "secondary"))
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
    fun `a save nobody can identify is quarantined for its owner to claim at login`() {
        portal.playerdata("primary", "Stranger", pos = Triple(8.5, 65.0, 9.5))
        portal.advancements("primary", "Stranger", """{"minecraft:story/root":{"done":true}}""")
        portal.stats("primary", "Stranger", """{"stats":{"minecraft:custom":{}}}""")
        portal.userCache("Stranger")

        val report = migrate(portal.plan(target).copy(skipUnidentified = true))

        assertEquals(1, report.quarantinedSaves)
        assertEquals(listOf("Stranger (${OfflineUuid.of("Stranger")}, primary)"), report.unidentifiedSaves)
        assertTrue(Files.exists(quarantined("primary", "$stranger.dat")), "the save itself was not quarantined")
        assertEquals(
            """{"minecraft:story/root":{"done":true}}""",
            Files.readString(quarantined("primary", "advancements/$stranger.json")),
        )
        assertTrue(Files.exists(quarantined("primary", "stats/$stranger.json")), "the stats sidecar")
        // Not in the level: vanilla must never walk a save keyed to nobody it knows.
        assertFalse(Files.exists(target.resolve("world/playerdata/$stranger.dat")))
        // ...and a copy migration still leaves the Portal's own tree complete.
        assertTrue(Files.exists(portal.primaryServerDir.resolve("world/playerdata/$stranger.dat")))
    }

    @Test
    fun `a quarantined player keeps one save per World they played in`() {
        portal.playerdata("primary", "Stranger")
        portal.playerdata("secondary", "Stranger")
        portal.userCache("Stranger")

        val report = migrate(portal.plan(target).copy(skipUnidentified = true))

        assertEquals(2, report.quarantinedSaves)
        assertTrue(Files.exists(quarantined("primary", "$stranger.dat")))
        assertTrue(Files.exists(quarantined("secondary", "$stranger.dat")))
    }

    @Test
    fun `moving a migration takes the quarantined saves out of the backend too`() {
        portal.playerdata("primary", "Stranger")
        portal.advancements("primary", "Stranger", """{}""")
        portal.userCache("Stranger")
        val backendSave = portal.primaryServerDir.resolve("world/playerdata/$stranger.dat")

        migrate(portal.plan(target).copy(skipUnidentified = true, worldTransfer = WorldTransfer.MOVE))

        assertTrue(Files.exists(quarantined("primary", "$stranger.dat")))
        assertFalse(Files.exists(backendSave), "a move must not leave a second copy behind")
        assertFalse(Files.exists(portal.primaryServerDir.resolve("world/advancements/$stranger.json")))
    }

    @Test
    fun `the report counts quarantined saves instead of reporting them left behind`() {
        portal.playerdata("primary", "Stranger")
        portal.userCache("Stranger")

        val report = migrate(portal.plan(target).copy(skipUnidentified = true))

        assertTrue(
            report.lines().any { it == "quarantined saves      : 1" },
            "no quarantine line in the report: ${report.lines()}",
        )
        assertTrue(
            report.lines().none { it.startsWith("LEFT BEHIND save") },
            "a quarantined save is not left behind: ${report.lines()}",
        )
    }

    @Test
    fun `a migration with nobody to quarantine leaves no quarantine directory behind`() {
        // What makes the claim cost a fresh server nothing: with no quarantine
        // directory, a login's whole involvement is one failed lookup.
        val report = migrate()

        assertEquals(0, report.quarantinedSaves)
        assertFalse(Files.exists(target.resolve("mctraveler/orphaned-saves")))
    }

    @Test
    fun `an operator nobody can identify is still reported as left behind`() {
        portal.ops("Notch", "Ghost")

        val report = migrate(portal.plan(target).copy(skipUnidentified = true))

        assertEquals(listOf("Ghost"), report.unidentifiedOperators)
        assertTrue(report.lines().any { it == "LEFT BEHIND operator   : Ghost" }, "${report.lines()}")
    }

    private val stranger: UUID get() = OfflineUuid.of("Stranger")

    private fun quarantined(world: String, name: String): Path =
        target.resolve("mctraveler/orphaned-saves/$world/$name")
}
