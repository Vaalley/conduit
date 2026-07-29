package eu.mctraveler.importer

import eu.mctraveler.persistence.JsonPlayerStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The per-player half of the migration, performed at login (ticket 20): the
 * username a joining player hands over unlocks the quarantined save named after
 * its offline hash.
 */
class OrphanedSaveClaimTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        /** What the Portal-era backends stamped their saves with; vanilla upgrades it on read. */
        private const val PORTAL_ERA_DATA_VERSION = 4536
    }

    @TempDir
    lateinit var dir: Path

    private val alice: UUID = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val aliceOffline: UUID get() = OfflineUuid.of("Alice")

    private val quarantine: SaveQuarantine by lazy { SaveQuarantine.under(dir.resolve("mctraveler")) }
    private val saves: Path by lazy { dir.resolve("world/players/data") }
    private val advancements: Path by lazy { dir.resolve("world/players/advancements") }
    private val stats: Path by lazy { dir.resolve("world/players/stats") }
    private val players: JsonPlayerStore by lazy { JsonPlayerStore(dir.resolve("mctraveler/players")) }

    private fun claim(uuid: UUID = alice, username: String = "Alice"): ClaimOutcome =
        OrphanedSaveClaim(quarantine, saves, advancements, stats, players).claim(uuid, username)

    @Test
    fun `a quarantined save becomes the live save under the player's Mojang uuid`() {
        quarantined("primary", pos = Triple(120.5, 68.0, -44.5)) { putInt("XpLevel", 27) }

        val outcome = claim()

        assertEquals(ClaimOutcome.Claimed("Alice", alice, "primary", null, PORTAL_ERA_DATA_VERSION), outcome)
        val save = liveSave()
        assertEquals(27, save.getIntOr("XpLevel", 0), "the live state the Portal left")
        assertEquals(120.5, save.getListOrEmpty("Pos").getDoubleOr(0, 0.0))
        assertEquals("minecraft:overworld", save.getStringOr("Dimension", ""))
    }

    @Test
    fun `the other World's save becomes that World's Per-World Bucket`() {
        quarantined(
            "primary",
            dimension = "minecraft:the_nether",
            pos = Triple(10.5, 70.0, -20.5),
            rotation = 30f to -5f,
        )
        quarantined("secondary", pos = Triple(500.5, 71.0, 600.5))
        players.setLastWorld(alice, "secondary")

        val outcome = claim() as ClaimOutcome.Claimed

        assertEquals("secondary", outcome.liveWorld)
        assertEquals("primary", outcome.bucketWorld)
        assertEquals(500.5, liveSave().getListOrEmpty("Pos").getDoubleOr(0, 0.0), "the last World's save is live")
        assertEquals("mctraveler:secondary", liveSave().getStringOr("Dimension", ""), "re-pointed at its World")
        val bucket = checkNotNull(players.bucket(alice, "primary"))
        assertEquals("nether", bucket.dimension)
        assertEquals(10.5, bucket.x)
        assertEquals(-20.5, bucket.z)
        assertEquals(30f, bucket.yaw)
        assertNull(players.bucket(alice, "secondary"), "the World they are in needs no bucket")
    }

    @Test
    fun `a player the Portal kept no record of is made live in Primary`() {
        quarantined("primary", pos = Triple(3.5, 64.0, 4.5))
        quarantined("secondary", pos = Triple(9.5, 64.0, 9.5))

        val outcome = claim() as ClaimOutcome.Claimed

        assertEquals("primary", outcome.liveWorld)
        assertEquals(3.5, liveSave().getListOrEmpty("Pos").getDoubleOr(0, 0.0))
        assertEquals("primary", players.lastWorld(alice), "login routing must agree with the live save")
    }

    @Test
    fun `a lastServer naming a World they have no save in falls back to the World they do`() {
        players.setLastWorld(alice, "secondary")
        quarantined("primary", pos = Triple(7.5, 64.0, 8.5))

        val outcome = claim() as ClaimOutcome.Claimed

        assertEquals("primary", outcome.liveWorld)
        assertNull(outcome.bucketWorld)
        assertEquals("primary", players.lastWorld(alice), "lastServer is rewritten to the World made live")
    }

    @Test
    fun `a player who already has a save is never overwritten`() {
        // The single most important line in this feature. A username can be
        // released and re-registered, so the offline hash of a name is not proof
        // of who owns the save behind it — a live player's own data always wins,
        // and the orphan is left for an operator to look at.
        quarantined("primary") { putInt("XpLevel", 99) }
        write(saves.resolve("$alice.dat"), CompoundTag().apply { putInt("XpLevel", 3) })

        val outcome = claim()

        assertEquals(ClaimOutcome.AlreadyLive("Alice", alice), outcome)
        assertEquals(3, liveSave().getIntOr("XpLevel", 0), "the live player's own save was replaced")
        assertTrue(Files.exists(quarantine.save("primary", aliceOffline)), "the orphan must be left alone")
        assertNull(players.lastWorld(alice), "nothing about the live player may be rewritten")
    }

    @Test
    fun `a save that survives only as vanilla's dat_old backup still counts as live`() {
        // PlayerDataStorage.load falls back to <uuid>.dat_old, so that file is a
        // live player's save as far as this server is concerned.
        quarantined("primary") { putInt("XpLevel", 99) }
        Files.createDirectories(saves)
        Files.writeString(saves.resolve("$alice.dat_old"), "vanilla's own backup")

        assertEquals(ClaimOutcome.AlreadyLive("Alice", alice), claim())
        assertTrue(Files.exists(quarantine.save("primary", aliceOffline)))
    }

    @Test
    fun `advancements and statistics come across from the live World only`() {
        quarantined("primary")
        quarantined("secondary")
        players.setLastWorld(alice, "secondary")
        write(quarantine.advancements("primary", aliceOffline), """{"primary":true}""")
        write(quarantine.advancements("secondary", aliceOffline), """{"secondary":true}""")
        write(quarantine.stats("secondary", aliceOffline), """{"stats":{}}""")

        claim()

        assertEquals("""{"secondary":true}""", Files.readString(advancements.resolve("$alice.json")))
        assertTrue(Files.exists(stats.resolve("$alice.json")))
    }

    @Test
    fun `a successful claim empties the quarantine so nothing can be claimed twice`() {
        quarantined("primary")
        quarantined("secondary")
        write(quarantine.advancements("primary", aliceOffline), "{}")
        write(quarantine.stats("secondary", aliceOffline), "{}")

        claim()

        for (world in listOf("primary", "secondary")) {
            for (file in quarantine.filesOf(world, aliceOffline)) {
                assertFalse(Files.exists(file), "$file survived the claim")
            }
        }
        assertEquals(ClaimOutcome.NoOrphan, claim(), "a second login finds nothing to claim")
    }

    @Test
    fun `a username nobody quarantined a save for is left alone`() {
        quarantined("primary", username = "Bob")

        assertEquals(ClaimOutcome.NoOrphan, claim(username = "Alice"))
        assertFalse(Files.exists(saves.resolve("$alice.dat")))
    }

    @Test
    fun `a server with no quarantine at all claims nothing`() {
        assertEquals(ClaimOutcome.NoOrphan, claim())
    }

    @Test
    fun `the username is hashed exactly as the offline-mode backend hashed it`() {
        quarantined("primary", username = "Alice")

        assertEquals(ClaimOutcome.NoOrphan, claim(username = "alice"), "the hash is case-sensitive")
    }

    @Test
    fun `a save this server cannot place fails the claim and leaves the quarantine whole`() {
        quarantined("primary", dimension = "aether:the_aether")

        val outcome = claim()

        assertTrue(outcome is ClaimOutcome.Failed, "expected a failed claim, got $outcome")
        assertTrue(Files.exists(quarantine.save("primary", aliceOffline)), "the quarantine must survive")
        assertFalse(Files.exists(saves.resolve("$alice.dat")), "nothing may be half-written")
        assertNull(players.lastWorld(alice))
    }

    /** A Portal-era backend save for [username], already in the quarantine under [world]. */
    private fun quarantined(
        world: String,
        username: String = "Alice",
        dimension: String = "minecraft:overworld",
        pos: Triple<Double, Double, Double> = Triple(1.5, 64.0, -2.5),
        rotation: Pair<Float, Float> = 0f to 0f,
        extras: CompoundTag.() -> Unit = {},
    ) {
        val tag = CompoundTag().apply {
            putInt("DataVersion", PORTAL_ERA_DATA_VERSION)
            putString("Dimension", dimension)
            put("Pos", ListTag().apply {
                add(DoubleTag.valueOf(pos.first))
                add(DoubleTag.valueOf(pos.second))
                add(DoubleTag.valueOf(pos.third))
            })
            put("Rotation", ListTag().apply {
                add(FloatTag.valueOf(rotation.first))
                add(FloatTag.valueOf(rotation.second))
            })
            extras()
        }
        write(quarantine.save(world, OfflineUuid.of(username)), tag)
    }

    private fun liveSave(uuid: UUID = alice): CompoundTag =
        NbtIo.readCompressed(saves.resolve("$uuid.dat"), NbtAccounter.unlimitedHeap())

    private fun write(file: Path, tag: CompoundTag) {
        Files.createDirectories(file.parent)
        NbtIo.writeCompressed(tag, file)
    }

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
