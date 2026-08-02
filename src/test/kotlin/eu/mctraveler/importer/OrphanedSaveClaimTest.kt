package eu.mctraveler.importer

import eu.mctraveler.persistence.JsonPlayerStore
import eu.mctraveler.persistence.PortalJson
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertArrayEquals
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

        /**
         * How far Secondary moved, for the claims below. Both axes are non-zero
         * and they differ in sign, so a transposed or mirrored coordinate cannot
         * pass by accident; the nether's eighth of it (1024, -512) is a whole
         * number of region files, as any real offset must be.
         */
        private val OFFSET = MergeOffset(8192, -4096)

        /** The night the merge ran, for the marker and for a swept record's stamp. */
        private val MERGED_AT: Instant = Instant.parse("2026-08-02T00:49:31Z")
    }

    @TempDir
    lateinit var dir: Path

    private val alice: UUID = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val aliceOffline: UUID get() = OfflineUuid.of("Alice")

    private val quarantine: SaveQuarantine by lazy { SaveQuarantine.under(dir.resolve("mctraveler")) }
    private val saves: Path by lazy { dir.resolve("world/players/data") }
    private val advancements: Path by lazy { dir.resolve("world/players/advancements") }
    private val stats: Path by lazy { dir.resolve("world/players/stats") }
    private val records: Path by lazy { dir.resolve("mctraveler/players") }
    private val players: JsonPlayerStore by lazy { JsonPlayerStore(records) }

    /**
     * A claim against this run directory, on a server the merge has been run on
     * when [merge] names an offset and on one it has not when it does not.
     *
     * The offset goes in by writing the marker the merge itself writes rather
     * than by handing it to the claim, because the claim path has no other way to
     * learn it — every test below therefore exercises the real file on the way
     * through (ticket 14).
     */
    private fun claim(
        uuid: UUID = alice,
        username: String = "Alice",
        merge: MergeOffset? = null,
    ): ClaimOutcome {
        merge?.let { mergedBy(it) }
        return OrphanedSaveClaim(quarantine, saves, advancements, stats, players, records, MergeMarker.of(dir))
            .claim(uuid, username)
    }

    /** This run directory as a merge that moved Secondary by [offset] left it. */
    private fun mergedBy(offset: MergeOffset) =
        write(dir.resolve(WorldMerge.MARKER_FILE), MergeMarker.contents(offset, MERGED_AT))

    /**
     * [uuid]'s record as the sweep left it: naming Primary, because there is one
     * World afterwards, with the World it named before kept in the merge stamp.
     */
    private fun swept(uuid: UUID, wasLastIn: WorldTrio) {
        players.setLastWorld(uuid, WorldLayout.PRIMARY.id)
        MergeStamp.into(records.resolve("$uuid.json"), OFFSET, MERGED_AT, wasLastIn)
    }

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
    fun `a save naming a dimension this server has never had is claimed into the overworld`() {
        // Deliberate change from refusing (see PlayerdataImport.roleOf): a save the
        // server cannot place is still that player's inventory, and the overworld is
        // where vanilla puts anyone whose dimension it cannot honour. Refusing would
        // strand them holding nothing.
        quarantined("primary", dimension = "aether:the_aether")

        val outcome = claim()

        assertTrue(outcome is ClaimOutcome.Claimed, "expected the save to be claimed, got $outcome")
        assertFalse(Files.exists(quarantine.save("primary", aliceOffline)), "the quarantine is consumed")
        assertTrue(Files.exists(saves.resolve("$alice.dat")), "their save is now theirs")
    }

    @Test
    fun `a claim that fails part-way through writing says so`() {
        // Something already in the live World's place — the one state that stops a
        // claim after it has begun. The audit line must not claim otherwise.
        quarantined("primary")
        write(quarantine.advancements("primary", aliceOffline), "{}")
        write(advancements.resolve("$alice.json"), "somebody else's progress")

        val outcome = claim()

        assertTrue(
            outcome is ClaimOutcome.Failed && outcome.anythingWritten,
            "expected a failure that admits it wrote, got $outcome",
        )
        assertEquals("somebody else's progress", Files.readString(advancements.resolve("$alice.json")))
        assertTrue(Files.exists(quarantine.save("primary", aliceOffline)), "the quarantine must survive")
        assertFalse(Files.exists(saves.resolve("$alice.dat")), "the live save is written last, so not at all")
    }

    // ---- claiming onto a merged server (ticket 10) ---------------------------
    //
    // Everything below runs against a server the merge has already been run on.
    // It is the whole live-code surface of that migration: the quarantine is
    // claimed lazily, so a save that comes back years after Secondary moved has
    // to be put down where the landmass went rather than where it used to be —
    // once and silently, with no second chance.

    @Test
    fun `a save from Secondary's quarantine arrives at its relocated position`() {
        quarantined("secondary", pos = Triple(1000.5, 70.0, -2000.5), rotation = 12f to -3f)

        val outcome = claim(merge = OFFSET) as ClaimOutcome.Claimed

        assertEquals(MergeOnClaim.Relocated(OFFSET), outcome.merge)
        val save = liveSave()
        assertEquals("minecraft:overworld", save.getStringOr("Dimension", ""), "the corresponding Primary dimension")
        assertEquals(1000.5 + 8192, save.getListOrEmpty("Pos").getDoubleOr(0, 0.0))
        assertEquals(70.0, save.getListOrEmpty("Pos").getDoubleOr(1, 0.0), "height is never offset")
        assertEquals(-2000.5 - 4096, save.getListOrEmpty("Pos").getDoubleOr(2, 0.0))
        assertEquals(12f, save.getListOrEmpty("Rotation").getFloatOr(0, 0f), "they arrive facing the way they left")
    }

    @Test
    fun `a save from Secondary's nether moves one eighth as far`() {
        // The ratio vanilla links portals across, so a portal pair that lined up
        // in Secondary still lines up once Secondary is part of Primary.
        quarantined("secondary", dimension = "minecraft:the_nether", pos = Triple(800.5, 70.0, -400.5))

        claim(merge = OFFSET)

        val save = liveSave()
        assertEquals("minecraft:the_nether", save.getStringOr("Dimension", ""))
        assertEquals(800.5 + 1024, save.getListOrEmpty("Pos").getDoubleOr(0, 0.0))
        assertEquals(-400.5 - 512, save.getListOrEmpty("Pos").getDoubleOr(2, 0.0))
    }

    @Test
    fun `everything a claimed Secondary save remembers moves with it`() {
        // The criterion the whole ticket turns on: a quarantined save names the
        // *vanilla* trio whichever backend wrote it, so every place in a save out
        // of Secondary's quarantine is a Secondary place — the bed, the death
        // location, the nether entry point, the boat they logged out in and every
        // compass they were carrying, however deeply nested.
        quarantined("secondary", pos = Triple(0.5, 64.0, 0.5)) {
            put("respawn", globalPos("minecraft:overworld", 100, 64, 200))
            put("LastDeathLocation", globalPos("minecraft:the_nether", 10, 50, 20))
            put("entered_nether_pos", vec3(500.0, 63.0, 600.0))
            put("RootVehicle", CompoundTag().apply {
                putIntArray("Attach", intArrayOf(1, 2, 3, 4))
                put("Entity", CompoundTag().apply {
                    putString("id", "minecraft:boat")
                    put("Pos", vec3(0.5, 64.0, 0.5))
                })
            })
            put("Inventory", ListTag().apply { add(shulkerBoxHoldingACompassFor(300, 70, 400)) })
            put("EnderItems", ListTag().apply { add(compassFor(700, 72, 800)) })
        }

        claim(merge = OFFSET)

        val save = liveSave()
        assertPos(intArrayOf(100 + 8192, 64, 200 - 4096), save.getCompoundOrEmpty("respawn"), "the respawn point")
        assertEquals(
            "minecraft:overworld",
            save.getCompoundOrEmpty("respawn").getStringOr("dimension", ""),
            "the bed is in the corresponding Primary dimension",
        )
        assertPos(
            intArrayOf(10 + 1024, 50, 20 - 512),
            save.getCompoundOrEmpty("LastDeathLocation"),
            "the last death location, so a recovery compass points at their items",
        )
        assertEquals(500.0 + 8192, save.getListOrEmpty("entered_nether_pos").getDoubleOr(0, 0.0))
        assertEquals(600.0 - 4096, save.getListOrEmpty("entered_nether_pos").getDoubleOr(2, 0.0))
        val vehicle = save.getCompoundOrEmpty("RootVehicle")
        assertEquals(0.5 + 8192, vehicle.getCompoundOrEmpty("Entity").getListOrEmpty("Pos").getDoubleOr(0, 0.0))
        assertArrayEquals(
            intArrayOf(1, 2, 3, 4),
            vehicle.getIntArray("Attach").orElseThrow(),
            "the seat uuid is an int array and not a place",
        )
        assertPos(intArrayOf(300 + 8192, 70, 400 - 4096), lodestoneIn(nestedCompass(save)), "a nested lodestone")
        assertPos(
            intArrayOf(700 + 8192, 72, 800 - 4096),
            lodestoneIn(save.getListOrEmpty("EnderItems").getCompoundOrEmpty(0)),
            "a lodestone in the ender chest",
        )
    }

    @Test
    fun `a claimed Secondary save is stamped exactly as the sweep would have stamped it`() {
        quarantined("secondary")

        claim(merge = OFFSET)

        val stamp = checkNotNull(PortalJson.parse(Files.readString(records.resolve("$alice.json")))["merge"])
        val at = Instant.parse(
            PortalJson.decodeString(checkNotNull(PortalJson.parse(stamp.rawValue)["at"]).rawValue),
        )
        assertEquals(
            MergeStamp.json(OFFSET, at),
            stamp.rawValue,
            "the claim writes the sweep's own stamp, not a second spelling of it",
        )
    }

    @Test
    fun `a save from Primary's quarantine is not moved`() {
        // Its owner was never anywhere that moved, so applying the offset would
        // put them somewhere they have never been.
        quarantined("primary", pos = Triple(120.5, 68.0, -44.5)) {
            put("respawn", globalPos("minecraft:overworld", 100, 64, 200))
        }

        val outcome = claim(merge = OFFSET) as ClaimOutcome.Claimed

        assertEquals(MergeOnClaim.LeftWhereItWas, outcome.merge)
        val save = liveSave()
        assertEquals(120.5, save.getListOrEmpty("Pos").getDoubleOr(0, 0.0))
        assertEquals(-44.5, save.getListOrEmpty("Pos").getDoubleOr(2, 0.0))
        assertPos(intArrayOf(100, 64, 200), save.getCompoundOrEmpty("respawn"), "an untouched respawn point")
        assertNull(
            PortalJson.parse(Files.readString(records.resolve("$alice.json")))["merge"],
            "a stamp would claim a move that never happened",
        )
    }

    @Test
    fun `a Secondary save banked as a Per-World Bucket is moved too`() {
        // Not the rare case: the sweep rewrote every existing record's lastServer
        // to Primary, so a returning player quarantined on both sides has their
        // Primary save made live and their Secondary one banked — which is
        // exactly the half that moved.
        quarantined("primary", pos = Triple(3.5, 64.0, 4.5))
        quarantined("secondary", dimension = "minecraft:the_nether", pos = Triple(600.5, 71.0, -800.5))
        players.setLastWorld(alice, "primary")

        val outcome = claim(merge = OFFSET) as ClaimOutcome.Claimed

        assertEquals("secondary", outcome.bucketWorld)
        val bucket = checkNotNull(players.bucket(alice, "secondary"))
        assertEquals(600.5 + 1024, bucket.x, "the banked position is in merged coordinates")
        assertEquals(-800.5 - 512, bucket.z)
        assertEquals(3.5, liveSave().getListOrEmpty("Pos").getDoubleOr(0, 0.0), "the Primary save is untouched")
    }

    @Test
    fun `a claim that applied the merge is recorded in Primary, the only World left`() {
        // A record still naming Secondary would have the login path place its
        // owner in a World that is being retired — the same rewrite the sweep
        // made to every record it swept.
        quarantined("secondary")

        claim(merge = OFFSET)

        assertEquals("primary", players.lastWorld(alice))
    }

    @Test
    fun `a merged server still never overwrites a player who already has a save`() {
        quarantined("secondary") { putInt("XpLevel", 99) }
        write(saves.resolve("$alice.dat"), CompoundTag().apply { putInt("XpLevel", 3) })

        assertEquals(ClaimOutcome.AlreadyLive("Alice", alice), claim(merge = OFFSET))
        assertEquals(3, liveSave().getIntOr("XpLevel", 0), "the live player's own save was replaced")
        assertTrue(Files.exists(quarantine.save("secondary", aliceOffline)), "the orphan must be left alone")
        assertFalse(Files.exists(records.resolve("$alice.json")), "nothing about the live player may be written")
    }

    @Test
    fun `a merged claim that cannot be made writes nothing and leaves the quarantine intact`() {
        // Worked out in full before a byte is written, so a save this server
        // cannot read fails while the quarantine is still whole and the next
        // login is free to try again.
        Files.createDirectories(quarantine.save("secondary", aliceOffline).parent)
        Files.writeString(quarantine.save("secondary", aliceOffline), "not NBT at all")

        val outcome = claim(merge = OFFSET)

        assertTrue(
            outcome is ClaimOutcome.Failed && !outcome.anythingWritten,
            "expected a failure that wrote nothing, got $outcome",
        )
        assertTrue(Files.exists(quarantine.save("secondary", aliceOffline)), "the quarantine must survive")
        assertFalse(Files.exists(saves.resolve("$alice.dat")), "no live save may be written")
        assertFalse(Files.exists(records.resolve("$alice.json")), "and no stamp either")
    }

    @Test
    fun `a claim that moved a save reads differently in the log from one that did not`() {
        // The only record of what happened to a returning player: the claim is
        // invisible to them and refused for good once they have a save of their
        // own, so "moved" and "should have been moved" must not look alike.
        val moved = OrphanedSaveClaimFeature.mergeClause(MergeOnClaim.Relocated(OFFSET))
        val untouched = OrphanedSaveClaimFeature.mergeClause(MergeOnClaim.LeftWhereItWas)

        assertTrue(moved.contains("x +8192") && moved.contains("x +1024"), "names the move it made: $moved")
        assertTrue(untouched.contains("not moved"), "says it made none: $untouched")
        assertEquals("", OrphanedSaveClaimFeature.mergeClause(MergeOnClaim.NotMerged), "an unmerged server is silent")
    }

    // ---- where the offset comes from (ticket 14) -----------------------------
    //
    // From the marker the merge wrote about itself, and from nowhere else. It
    // used to be a constant an operator edited in source between planning the
    // merge and running it; forgetting that step left every one of these claims
    // moving nobody, silently, for the whole life of the quarantine.

    @Test
    fun `the offset comes out of the merge marker, exactly as the merge writes it`() {
        // The marker's bytes, spelled out rather than produced by MergeMarker, so
        // that this fails if the format the merge writes and the format the claim
        // path reads ever stop being the same one.
        quarantined("secondary", pos = Triple(1000.5, 70.0, -2000.5))
        write(
            dir.resolve(WorldMerge.MARKER_FILE),
            """{"mergedAt":"2026-08-02T00:49:31Z","offsetX":8192,"offsetZ":-4096}""" + "\n",
        )

        val outcome = claim() as ClaimOutcome.Claimed

        assertEquals(MergeOnClaim.Relocated(OFFSET), outcome.merge)
        assertEquals(1000.5 + 8192, liveSave().getListOrEmpty("Pos").getDoubleOr(0, 0.0))
        assertEquals(-2000.5 - 4096, liveSave().getListOrEmpty("Pos").getDoubleOr(2, 0.0))
    }

    @Test
    fun `a run directory carrying no merge marker moves nobody, exactly as before`() {
        // The state of every server that never merged, and the one case where
        // "this claim moved nothing" is the right answer rather than a failure.
        quarantined("secondary", pos = Triple(1000.5, 70.0, -2000.5))

        val outcome = claim() as ClaimOutcome.Claimed

        assertEquals(MergeOnClaim.NotMerged, outcome.merge)
        assertEquals(1000.5, liveSave().getListOrEmpty("Pos").getDoubleOr(0, 0.0))
        assertEquals("mctraveler:secondary", liveSave().getStringOr("Dimension", ""), "re-pointed, not moved")
        assertNull(
            PortalJson.parse(Files.readString(records.resolve("$alice.json")))["merge"],
            "a stamp would claim a merge that never happened",
        )
    }

    @Test
    fun `a merge marker that cannot be read fails the claim rather than moving nobody`() {
        // The failure this correction exists to delete. A save that says it was
        // merged and cannot say by how far must not read as a save that was never
        // merged: that would put every returning player back at their pre-merge
        // coordinates, once each and with nobody watching. It fails while the
        // quarantine is whole, so the next login after a repair claims normally.
        val damaged = listOf(
            "a marker overwritten by something that is not JSON" to
                "the merge stamp, clobbered",
            "a marker with no offset in it at all" to
                """{"mergedAt":"2026-08-02T00:49:31Z"}""",
            "a marker missing one of its two axes" to
                """{"mergedAt":"2026-08-02T00:49:31Z","offsetX":8192}""",
            "an axis that is not a number of blocks" to
                """{"mergedAt":"2026-08-02T00:49:31Z","offsetX":"8192","offsetZ":-4096}""",
            "an axis off the lattice every real offset lands on" to
                """{"mergedAt":"2026-08-02T00:49:31Z","offsetX":100,"offsetZ":-4096}""",
            "a move no merge can have applied, which would move nobody just as quietly" to
                """{"mergedAt":"2026-08-02T00:49:31Z","offsetX":0,"offsetZ":0}""",
        )

        for ((what, contents) in damaged) {
            quarantined("secondary", pos = Triple(1000.5, 70.0, -2000.5))
            write(dir.resolve(WorldMerge.MARKER_FILE), contents)

            val outcome = claim()

            assertTrue(
                outcome is ClaimOutcome.Failed && !outcome.anythingWritten,
                "$what must fail the claim having written nothing, got $outcome",
            )
            val failed = outcome as ClaimOutcome.Failed
            assertTrue(
                failed.reason.contains("merge.json"),
                "$what must name the file an operator has to repair: ${failed.reason}",
            )
            assertTrue(Files.exists(quarantine.save("secondary", aliceOffline)), "$what: the quarantine must survive")
            assertFalse(Files.exists(saves.resolve("$alice.dat")), "$what: no live save may be written")
            assertFalse(Files.exists(records.resolve("$alice.json")), "$what: and no stamp either")
        }
    }

    // ---- which of two quarantined saves becomes live (ticket 14) -------------

    @Test
    fun `a player quarantined on both sides is made live in the World they were last in`() {
        // The sweep rewrote their record's lastServer to Primary — correctly,
        // there is one World now — so the record can no longer answer this, and
        // reading it would hand them the wrong save's inventory and XP. Their
        // coordinates would be right either way, which is what makes it quiet.
        quarantined("primary", pos = Triple(3.5, 64.0, 4.5)) { putInt("XpLevel", 7) }
        quarantined("secondary", pos = Triple(1000.5, 70.0, -2000.5)) { putInt("XpLevel", 42) }
        swept(alice, wasLastIn = WorldLayout.SECONDARY)

        val outcome = claim(merge = OFFSET) as ClaimOutcome.Claimed

        assertEquals("secondary", outcome.liveWorld, "the World they were actually last in")
        assertEquals(MergeOnClaim.Relocated(OFFSET), outcome.merge)
        assertEquals(42, liveSave().getIntOr("XpLevel", 0), "the inventory and XP of the save they left off in")
        assertEquals(1000.5 + 8192, liveSave().getListOrEmpty("Pos").getDoubleOr(0, 0.0), "moved with the landmass")
        // And the other half seeds the Per-World Bucket, untransformed, because
        // it came out of Primary's quarantine and nothing of Primary's moved.
        assertEquals("primary", outcome.bucketWorld)
        val bucket = checkNotNull(players.bucket(alice, "primary"))
        assertEquals(3.5, bucket.x)
        assertEquals(4.5, bucket.z)
        assertEquals("primary", players.lastWorld(alice), "the record still names the one World that is left")
    }

    @Test
    fun `the pre-merge World survives the claim's own stamp`() {
        // The claim rewrites the stamp with its own instant, and must not take
        // the merge's account of what it found here down with it.
        quarantined("primary")
        quarantined("secondary")
        swept(alice, wasLastIn = WorldLayout.SECONDARY)

        claim(merge = OFFSET)

        assertEquals(
            WorldLayout.SECONDARY,
            MergeStamp.wasLastIn(records.resolve("$alice.json")),
            "the World the sweep found is still on the record",
        )
    }

    @Test
    fun `a player quarantined on one side only is unaffected by what the sweep recorded`() {
        quarantined("primary", pos = Triple(3.5, 64.0, 4.5))
        swept(alice, wasLastIn = WorldLayout.SECONDARY)

        val outcome = claim(merge = OFFSET) as ClaimOutcome.Claimed

        assertEquals("primary", outcome.liveWorld, "the only save they have")
        assertNull(outcome.bucketWorld)
        assertEquals(MergeOnClaim.LeftWhereItWas, outcome.merge)
        assertEquals(3.5, liveSave().getListOrEmpty("Pos").getDoubleOr(0, 0.0), "and it did not move")
    }

    @Test
    fun `a player the sweep never saw still claims by the record's own lastServer`() {
        // Nobody swept them, because at cutover they had no record to sweep — so
        // whatever their record says now, it says it for itself, and there is no
        // stamp to prefer over it.
        quarantined("primary", pos = Triple(3.5, 64.0, 4.5))
        quarantined("secondary", pos = Triple(1000.5, 70.0, -2000.5))
        players.setLastWorld(alice, "secondary")

        val outcome = claim(merge = OFFSET) as ClaimOutcome.Claimed

        assertEquals("secondary", outcome.liveWorld)
        assertEquals(1000.5 + 8192, liveSave().getListOrEmpty("Pos").getDoubleOr(0, 0.0))
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

    /** Vanilla's `GlobalPos`: the shape the respawn point, a death location and every lodestone target take. */
    private fun globalPos(dimension: String, x: Int, y: Int, z: Int): CompoundTag = CompoundTag().apply {
        putString("dimension", dimension)
        putIntArray("pos", intArrayOf(x, y, z))
    }

    private fun vec3(x: Double, y: Double, z: Double): ListTag = ListTag().apply {
        add(DoubleTag.valueOf(x))
        add(DoubleTag.valueOf(y))
        add(DoubleTag.valueOf(z))
    }

    private fun compassFor(x: Int, y: Int, z: Int): CompoundTag = CompoundTag().apply {
        putString("id", "minecraft:compass")
        put("components", CompoundTag().apply {
            put("minecraft:lodestone_tracker", CompoundTag().apply {
                put("target", globalPos("minecraft:overworld", x, y, z))
                putBoolean("tracked", true)
            })
        })
    }

    /** A compass inside a shulker box inside the inventory — the nesting the ticket names. */
    private fun shulkerBoxHoldingACompassFor(x: Int, y: Int, z: Int): CompoundTag = CompoundTag().apply {
        putString("id", "minecraft:shulker_box")
        put("components", CompoundTag().apply {
            put("minecraft:container", ListTag().apply {
                add(CompoundTag().apply {
                    putInt("slot", 0)
                    put("item", compassFor(x, y, z))
                })
            })
        })
    }

    private fun nestedCompass(save: CompoundTag): CompoundTag =
        save.getListOrEmpty("Inventory").getCompoundOrEmpty(0)
            .getCompoundOrEmpty("components").getListOrEmpty("minecraft:container")
            .getCompoundOrEmpty(0).getCompoundOrEmpty("item")

    private fun lodestoneIn(compass: CompoundTag): CompoundTag =
        compass.getCompoundOrEmpty("components")
            .getCompoundOrEmpty("minecraft:lodestone_tracker")
            .getCompoundOrEmpty("target")

    private fun assertPos(expected: IntArray, globalPos: CompoundTag, what: String) =
        assertArrayEquals(expected, globalPos.getIntArray("pos").orElseThrow(), what)

    private fun write(file: Path, tag: CompoundTag) {
        Files.createDirectories(file.parent)
        NbtIo.writeCompressed(tag, file)
    }

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
