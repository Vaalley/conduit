package eu.mctraveler.importer

import eu.mctraveler.persistence.JsonPlayerStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.SharedConstants
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
 * The second half of the cutover: the retired Nucleus server's embassies, their
 * regions and their owners' crystal energy land in the already-migrated run
 * directory, once, and never on top of anything (spec User Stories 38–39).
 */
class EmbassyImportTest {
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

    private lateinit var nucleus: NucleusDeploymentFixture

    private val jam = NucleusDeploymentFixture.JAM
    private val nomad = NucleusDeploymentFixture.NOMAD
    private val stranger = UUID.fromString("22222222-3333-4444-8555-666666666666")

    private val target: Path get() = nucleus.targetDir
    private val store: JsonPlayerStore get() = JsonPlayerStore(nucleus.playersDir)

    @BeforeEach
    fun buildDeployment() {
        nucleus = NucleusDeploymentFixture(dir).build()
        nucleus.playerdata(jam, energy = 1, nextRegenAt = 987_654)
        nucleus.playerdata(nomad, energy = 0)
        nucleus.playerdataWithoutTags(stranger)
    }

    private fun import(worldTransfer: WorldTransfer = WorldTransfer.COPY) =
        EmbassyImport(nucleus.plan(worldTransfer)).run()

    // ---- the happy path -----------------------------------------------------

    @Test
    fun `the plots, the regions and the energy all arrive`() {
        val report = import()

        assertEquals(2, report.regions)
        assertEquals(2, report.playersImported)
        assertEquals(0, report.playersSkipped)
        assertEquals(4, report.chunkFiles)
        assertTrue(report.worldBytes > 0)
        assertEquals("BukkitValues", report.container)
    }

    @Test
    fun `the chunk data lands where the dimension will look for it`() {
        import()

        assertEquals(
            "chunk bytes of embassies/region/r.0.0.mca",
            Files.readString(nucleus.embassiesDimension.resolve("region/r.0.0.mca")),
        )
        assertTrue(Files.exists(nucleus.embassiesDimension.resolve("region/r.-1.0.mca")))
        assertTrue(Files.exists(nucleus.embassiesDimension.resolve("entities/r.0.0.mca")))
        assertTrue(Files.exists(nucleus.embassiesDimension.resolve("poi/r.0.0.mca")))
    }

    @Test
    fun `the region file is the target's own, with the embassies appended`() {
        import()

        assertEquals(NucleusDeploymentFixture.EXPECTED_REGIONS, Files.readString(nucleus.regionsFile))
    }

    @Test
    fun `the regions already in the target come through byte for byte`() {
        val before = Files.readString(nucleus.regionsFile)

        import()

        // Everything up to and including the last existing region's closing brace.
        assertTrue(Files.readString(nucleus.regionsFile).startsWith(before.removeSuffix("\n  }\n}")))
    }

    @Test
    fun `energy and the recharge threshold land in the player store`() {
        import()

        assertEquals(1, store.crystalEnergy(jam))
        assertEquals(987_654, store.crystalNextRegenAt(jam))
        assertEquals(0, store.crystalEnergy(nomad))
        assertNull(store.crystalNextRegenAt(nomad))
    }

    @Test
    fun `a player who never touched a crystal gets no record at all`() {
        import()

        assertFalse(Files.exists(nucleus.playersDir.resolve("$stranger.json")))
    }

    @Test
    fun `a record's other fields pass through the import untouched`() {
        nucleus.targetPlayer(jam, """{"lastServer":"secondary","balance":1234.50,"isAdmin":true}""")

        import()

        assertEquals(
            """{"lastServer":"secondary","balance":1234.50,"isAdmin":true,""" +
                """"crystalEnergy":1,"crystalNextRegenAt":987654}""",
            Files.readString(nucleus.playersDir.resolve("$jam.json")),
        )
    }

    @Test
    fun `a player who has spent energy on the new server is never overwritten`() {
        nucleus.targetPlayer(jam, """{"crystalEnergy":3}""")

        val report = import()

        assertEquals(1, report.playersImported)
        assertEquals(1, report.playersSkipped)
        assertEquals(3, store.crystalEnergy(jam))
        assertNull(store.crystalNextRegenAt(jam))
    }

    @Test
    fun `a save whose energy is out of range is clamped and named`() {
        nucleus.playerdata(nomad, energy = 9)

        val report = import()

        assertEquals(5, store.crystalEnergy(nomad))
        assertEquals(listOf("$nomad had 9"), report.clampedEnergies)
    }

    @Test
    fun `a playerdata file that is not named after a uuid is left alone`() {
        nucleus.strayPlayerdata("069a79f4-44e9-4726-a5be-fca90e38aaf5-1.dat")

        val report = import()

        assertEquals(listOf("069a79f4-44e9-4726-a5be-fca90e38aaf5-1.dat"), report.unnamedFiles)
        assertEquals(2, report.playersImported)
    }

    @Test
    fun `an embassy pointing at a world this server does not have is reported`() {
        nucleus.nucleusRegions(
            NucleusDeploymentFixture.NUCLEUS_REGIONS
                .replace("\"world\":\"last_nether\"}", "\"world\":\"creative\"}"),
        )

        val report = import()

        assertEquals(2, report.regions)
        assertEquals(listOf("Nomad's Embassy → \"creative\""), report.unknownDestinationWorlds)
    }

    @Test
    fun `chunk folders the old world never had are reported, not refused`() {
        deleteRecursively(nucleus.sourceWorld.resolve("poi"))

        val report = import()

        assertEquals(listOf("poi"), report.missingChunkDirectories)
        assertFalse(Files.exists(nucleus.embassiesDimension.resolve("poi")))
    }

    // ---- copy and move ------------------------------------------------------

    @Test
    fun `copying leaves the Nucleus world where it was`() {
        import(WorldTransfer.COPY)

        assertTrue(Files.exists(nucleus.sourceWorld.resolve("region/r.0.0.mca")))
    }

    @Test
    fun `moving takes the chunk data out of the Nucleus world`() {
        val report = import(WorldTransfer.MOVE)

        assertEquals(4, report.chunkFiles)
        assertTrue(report.worldBytes > 0)
        assertFalse(Files.exists(nucleus.sourceWorld.resolve("region/r.0.0.mca")))
        assertEquals(
            "chunk bytes of embassies/region/r.0.0.mca",
            Files.readString(nucleus.embassiesDimension.resolve("region/r.0.0.mca")),
        )
    }

    // ---- refusals -----------------------------------------------------------

    @Test
    fun `a second run refuses rather than importing twice`() {
        import()

        val refusal = assertThrows(MigrationRefused::class.java) { import() }

        assertEquals(
            "${nucleus.embassiesDimension} already exists — the embassies have been imported already",
            refusal.message,
        )
    }

    @Test
    fun `a target that already holds embassy regions refuses`() {
        import()
        deleteRecursively(nucleus.embassiesDimension)

        val refusal = assertThrows(MigrationRefused::class.java) { import() }

        assertEquals(
            "${nucleus.regionsFile} already holds 2 region(s) in world \"embassies\" " +
                "(first: \"Jam's Embassy\") — the embassy regions have been imported already",
            refusal.message,
        )
    }

    @Test
    fun `an embassy region anywhere in the target's tree refuses`() {
        nucleus.targetRegions(
            """
            {"regions":{"0":{"title":"Wanderer's Keep","start-x":-10,"start-z":-10,"end-x":10,"end-z":10,
            "world":"last_nether","members":[],"sub-regions":{"0":{"title":"Smuggled Embassy","start-x":3,
            "start-z":3,"end-x":13,"end-z":13,"world":"embassies","members":[]}}}}}
            """.trimIndent(),
        )

        val refusal = assertThrows(MigrationRefused::class.java) { import() }

        assertEquals(
            "${nucleus.regionsFile} already holds 1 region(s) in world \"embassies\" " +
                "(first: \"Smuggled Embassy\") — the embassy regions have been imported already",
            refusal.message,
        )
    }

    @Test
    fun `a run directory the migration never wrote refuses`() {
        Files.delete(nucleus.regionsFile)

        val refusal = assertThrows(MigrationRefused::class.java) { import() }

        assertEquals(
            "$target has no \"regions.json\" — import into the migrated server run directory " +
                "(see docs/migration.md), not a fresh one",
            refusal.message,
        )
    }

    @Test
    fun `a target that is not a directory refuses`() {
        val plan = nucleus.plan().copy(targetDir = dir.resolve("nowhere"))

        val refusal = assertThrows(MigrationRefused::class.java) { EmbassyImport(plan).run() }

        assertEquals("${dir.resolve("nowhere")} is not a directory", refusal.message)
    }

    @Test
    fun `a Nucleus directory with no embassies world refuses`() {
        deleteRecursively(nucleus.sourceWorld)

        val refusal = assertThrows(MigrationRefused::class.java) { import() }

        assertEquals(
            "${nucleus.sourceWorld} does not exist — is ${nucleus.oldDir} the Nucleus server directory?",
            refusal.message,
        )
    }

    @Test
    fun `an embassies world with no chunk data refuses`() {
        deleteRecursively(nucleus.sourceWorld.resolve("region"))

        val refusal = assertThrows(MigrationRefused::class.java) { import() }

        assertEquals(
            "${nucleus.sourceWorld.resolve("region")} does not exist — " +
                "is ${nucleus.oldDir} the Nucleus server directory?",
            refusal.message,
        )
    }

    @Test
    fun `a Nucleus directory with no region file refuses`() {
        val regions = nucleus.oldDir.resolve("plugins/MCTravelerNucleus/regions.json")
        Files.delete(regions)

        val refusal = assertThrows(MigrationRefused::class.java) { import() }

        assertEquals("$regions does not exist — is ${nucleus.oldDir} the Nucleus server directory?", refusal.message)
    }

    @Test
    fun `a Nucleus directory with no playerdata refuses`() {
        val playerdata = nucleus.oldDir.resolve("world/playerdata")
        deleteRecursively(playerdata)

        val refusal = assertThrows(MigrationRefused::class.java) { import() }

        assertEquals(
            "$playerdata does not exist — is ${nucleus.oldDir} the Nucleus server directory?",
            refusal.message,
        )
    }

    @Test
    fun `a staging directory left by an interrupted run refuses`() {
        val staging = target.resolve(".mctraveler-embassy-import")
        Files.createDirectories(staging)

        val refusal = assertThrows(MigrationRefused::class.java) { import() }

        assertEquals(
            "$staging is left over from an interrupted import; remove it and run again",
            refusal.message,
        )
    }

    // ---- all or nothing -----------------------------------------------------

    @Test
    fun `a region file the importer cannot read leaves the target exactly as it was`() {
        val before = Files.readString(nucleus.regionsFile)
        nucleus.nucleusRegions(
            NucleusDeploymentFixture.NUCLEUS_REGIONS.replace("\"yaw\":90.0,", ""),
        )

        assertThrows(IllegalArgumentException::class.java) { import() }

        assertEquals(before, Files.readString(nucleus.regionsFile))
        assertFalse(Files.exists(nucleus.embassiesDimension))
        assertFalse(Files.exists(target.resolve(".mctraveler-embassy-import")))
        assertNull(store.crystalEnergy(jam))
    }

    @Test
    fun `a playerdata file the importer cannot read leaves the target exactly as it was`() {
        Files.writeString(nucleus.oldDir.resolve("world/playerdata/$jam.dat"), "not a save")

        assertThrows(IllegalStateException::class.java) { import() }

        assertEquals(NucleusDeploymentFixture.TARGET_REGIONS, Files.readString(nucleus.regionsFile))
        assertFalse(Files.exists(nucleus.embassiesDimension))
        assertFalse(Files.exists(target.resolve(".mctraveler-embassy-import")))
        assertNull(store.crystalEnergy(nomad))
    }

    private fun deleteRecursively(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
