package eu.mctraveler.importer

import eu.mctraveler.embassy.EmbassyDestination
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionService
import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Secondary's End, and the two things ticket 07 puts around it: the gate that
 * will not destroy other people's builds by accident, and the cross-check that
 * a respawn point and the bed it names came out of the merge agreeing with each
 * other.
 *
 * Driven through the merge command end to end against [MergedDeploymentFixture]
 * (merge spec, "Testing Decisions"), a sibling of [WorldMergeTest] rather than
 * more of it. The offset is passed rather than searched, and both its axes are
 * in play — `x +8192, z -4096` in the overworld — so a landing that lost the Z
 * shift or flipped its sign could not pass. Every coordinate below is arrived at
 * by hand from those two numbers.
 */
class WorldMergeEndGateTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        private val OFFSET = MergeOffset(8192, -4096)

        private val ALICE = UUID.fromString("11111111-2222-4333-8444-555555555555")
        private val BOB = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa")

        private const val SECONDARY_END = "mctraveler:secondary_end"
        private const val SECONDARY_OVERWORLD = "mctraveler:secondary"
    }

    @TempDir
    lateinit var dir: Path

    private lateinit var deployment: MergedDeploymentFixture

    @BeforeEach
    fun buildDeployment() {
        deployment = MergedDeploymentFixture(dir).build()
    }

    private fun merge(acceptEndLoss: Boolean = false): MergeReport =
        WorldMerge(deployment.plan(offset = OFFSET, planOnly = false, acceptEndLoss = acceptEndLoss)).run()

    private fun refusal(acceptEndLoss: Boolean = false): String =
        assertThrows(MigrationRefused::class.java) { merge(acceptEndLoss) }.message.orEmpty()

    // ---- the refusal, and what it has to be good enough to do ----------------

    @Test
    fun `a Region still standing in Secondary's End refuses the merge, and names who to tell`() {
        deployment.withRegions(endRegionFile)
        deployment.withUserCache(ALICE to "Alice")

        val message = refusal()

        // Named by title and by its members, because the operator's next action
        // is to go and tell them. BOB is not in the profile cache and comes out
        // as his uuid, which is still something they can look up.
        assertTrue(
            message.contains("""the Region "Ender Outpost" — Alice, $BOB"""),
            message,
        )
        assertTrue(message.contains("--accept-end-loss"), message)
    }

    @Test
    fun `an Embassy destination pointing into Secondary's End is named in the refusal`() {
        deployment.withRegions(endDestinationFile)

        val message = refusal()

        assertTrue(
            message.contains("""the Embassy Region "Ambassador Plot", whose destination points into it"""),
            message,
        )
    }

    @Test
    fun `the refusal counts the players in the End and states where each of them would land`() {
        deployment.withWorldSpawn(100, 65, -200)
        deployment.playerSave(ALICE, save(SECONDARY_END))
        deployment.playerRecord(ALICE, """{"lastServer":"secondary","worlds":{"secondary":$secondaryBase}}""")
        deployment.playerSave(BOB, save(SECONDARY_END))

        val message = refusal()

        assertTrue(message.contains("2 players standing in it, who would land at:"), message)
        assertTrue(message.contains("1 at their own Secondary base"), message)
        assertTrue(message.contains("1 at the relocated Secondary spawn"), message)
        // 10.5 + 8192, unchanged, 20.5 − 4096.
        assertTrue(message.contains("$ALICE → their own Secondary base, x 8202.5, y 70.0, z -4075.5"), message)
        // 100 + 0.5 + 8192, unchanged, −200 + 0.5 − 4096.
        assertTrue(
            message.contains("$BOB → the relocated Secondary spawn, x 8292.5, y 65.0, z -4295.5"),
            message,
        )
    }

    @Test
    fun `a refusal leaves the run directory exactly as it was, chunks and all`() {
        // The relocation runs before this gate does, so by now a whole staged
        // merge exists and has to be thrown away rather than committed.
        deployment.withRealSecondaryChunks()
        deployment.withRegions(endRegionFile)
        val before = deployment.contents()

        refusal()

        assertEquals(before, deployment.contents())
        assertFalse(Files.exists(deployment.staging), "the staging directory outlived the refusal")
    }

    @Test
    fun `a merge with nothing anchored in the End says so rather than staying quiet`() {
        val report = merge()

        assertEquals(
            listOf("Secondary's End          : discarded — nothing was anchored in it"),
            report.section<MergeEndReport>().lines(),
        )
    }

    // ---- what the opt-in destroys -------------------------------------------

    @Test
    fun `with the opt-in the End Regions go, and every other Region is left where it was`() {
        deployment.withRegions(endRegionFile)

        merge(acceptEndLoss = true)

        val roots = RegionService(deployment.regionsFile).roots
        assertEquals(listOf("Home"), roots.map(Region::title))
        // The nest goes with its parent: a sub-region of a Region in the End was
        // one protected place and stops being one all at once.
        assertEquals(emptyList<String>(), roots.flatMap { it.subRegions }.map(Region::title))
    }

    @Test
    fun `with the opt-in an Embassy destination into the End is cleared, not left aiming at nothing`() {
        deployment.withRegions(endDestinationFile)

        merge(acceptEndLoss = true)

        val plot = RegionService(deployment.regionsFile).roots.first { it.title == "Ambassador Plot" }
        assertEquals(null, EmbassyDestination.of(plot))
        // The Region itself is untouched: it stands in the embassies world, which
        // the merge does not move, and only its destination named the End.
        assertEquals("embassies", plot.world)
        assertTrue(plot.flags.contains(Region.EMBASSY_FLAG))
    }

    @Test
    fun `a player standing in the End lands at the Secondary base they banked`() {
        deployment.playerSave(
            ALICE,
            save(SECONDARY_END, x = 1.0, y = 50.0, z = 2.0) {
                // Their boat is in a dimension that stops existing; vanilla would
                // remount them on it at its own saved position and drag them back.
                put("RootVehicle", CompoundTag().apply { put("Entity", CompoundTag()) })
            },
        )
        deployment.playerRecord(ALICE, """{"lastServer":"secondary","worlds":{"secondary":$secondaryBase}}""")

        merge(acceptEndLoss = true)

        val live = deployment.savedPlayer(ALICE)
        assertEquals("minecraft:overworld", live.getStringOr("Dimension", ""))
        assertEquals(listOf(8202.5, 70.0, -4075.5), positionIn(live))
        assertFalse(live.contains("RootVehicle"), "the boat they left in the End came with them")
    }

    @Test
    fun `a player standing in the End with nothing banked lands at the relocated Secondary spawn`() {
        deployment.withWorldSpawn(100, 65, -200)
        deployment.playerSave(BOB, save(SECONDARY_END, x = 1.0, y = 50.0, z = 2.0))
        // A bucket in Secondary's *nether* is not somewhere to put them: out of a
        // deleted dimension into a lava-lit cave is worse than a spawn everyone
        // knows.
        deployment.playerRecord(
            BOB,
            """{"lastServer":"secondary","worlds":{"secondary":""" +
                """{"dimension":"nether","x":16.0,"y":70.0,"z":-8.0,"yaw":0.0,"pitch":0.0}}}""",
        )

        merge(acceptEndLoss = true)

        val live = deployment.savedPlayer(BOB)
        assertEquals("minecraft:overworld", live.getStringOr("Dimension", ""))
        assertEquals(listOf(8292.5, 65.0, -4295.5), positionIn(live))
    }

    @Test
    fun `no player is left naming a dimension that will not exist, wherever they were standing`() {
        // Nothing of Bob's is *anchored* in the End — he is in Primary and the
        // gate has nothing to refuse over — but four things he owns still name a
        // dimension the merge deletes, and after it they would name nothing.
        deployment.playerSave(
            BOB,
            save("minecraft:overworld", x = 10.5, y = 64.0, z = 20.5) {
                put("respawn", globalPos(SECONDARY_END, 1, 60, 2))
                put("LastDeathLocation", globalPos(SECONDARY_END, 3, 60, 4))
                put("EnderItems", ListTag().apply { add(compass(SECONDARY_END, 5, 60, 6)) })
                put(
                    "ender_pearls",
                    ListTag().apply {
                        add(
                            CompoundTag().apply {
                                putString("id", "minecraft:ender_pearl")
                                putString("ender_pearl_dimension", SECONDARY_END)
                            },
                        )
                    },
                )
            },
        )

        merge()

        val live = deployment.savedPlayer(BOB)
        // A respawn point with no dimension left respawns you at the world spawn,
        // and a compass with no target spins — which is what both already do the
        // moment the block behind them is broken.
        assertFalse(live.contains("respawn"), "a bed in a deleted dimension survived")
        assertFalse(live.contains("LastDeathLocation"), "a death in a deleted dimension survived")
        assertFalse(
            live.getListOrEmpty("EnderItems").getCompoundOrEmpty(0)
                .getCompoundOrEmpty("components")
                .getCompoundOrEmpty("minecraft:lodestone_tracker")
                .contains("target"),
            "a compass still points into a deleted dimension",
        )
        assertEquals(0, live.getListOrEmpty("ender_pearls").size)
        // And nothing else of his moved: he was in Primary the whole time.
        assertEquals(listOf(10.5, 64.0, 20.5), positionIn(live))
    }

    @Test
    fun `the report records what was dropped, so there is something to communicate from`() {
        deployment.withWorldSpawn(100, 65, -200)
        deployment.withRegions(endRegionFile)
        deployment.withUserCache(ALICE to "Alice")
        deployment.playerSave(BOB, save(SECONDARY_END))

        val report = merge(acceptEndLoss = true)

        assertEquals(
            listOf(
                "Secondary's End          : discarded, and the loss accepted with --accept-end-loss",
                """  Region deleted         : the Region "Ender Outpost" — Alice, $BOB""",
                """  Region deleted         : the Region "Ender Cellar", which has no members""",
                "  player landed          : $BOB → the relocated Secondary spawn, " +
                    "x 8292.5, y 65.0, z -4295.5",
            ),
            report.section<MergeEndReport>().lines(),
        )
    }

    // ---- the respawn point and the bed it names ------------------------------

    @Test
    fun `a respawn point whose bed arrived with it is confirmed against the relocated chunks`() {
        deployment.withRealSecondaryChunks(withABedAt(BlockPos(2, 64, 3)))
        deployment.playerSave(
            ALICE,
            save(SECONDARY_OVERWORLD) { put("respawn", globalPos(SECONDARY_OVERWORLD, 2, 64, 3)) },
        )

        val report = merge()

        // 2 + 8192 and 3 − 4096, in the relocated copy of Secondary's chunk (0,0).
        assertEquals(
            listOf(8194, 64, -4093),
            deployment.savedPlayer(ALICE).getCompoundOrEmpty("respawn").getIntArray("pos").orElseThrow().toList(),
        )
        assertEquals(RespawnCheckReport(confirmed = 1, alreadyWithoutABed = 0), report.section<RespawnCheckReport>())
    }

    @Test
    fun `a respawn point whose bed did not survive relocation fails the merge, naming both`() {
        // The bed stands in the frontier chunk, which vanilla never finished and
        // the merge therefore drops rather than relocates. The respawn point is
        // moved by the player sweep all the same, and the two passes disagree.
        deployment.withRealSecondaryChunks(withABedInTheFrontier(BlockPos(112, 64, 112)))
        deployment.playerSave(
            ALICE,
            save(SECONDARY_OVERWORLD) { put("respawn", globalPos(SECONDARY_OVERWORLD, 112, 64, 112)) },
        )
        val before = deployment.contents()

        val message = assertThrows(MigrationRefused::class.java) { merge() }.message.orEmpty()

        assertTrue(message.contains("player $ALICE respawns at x 112, y 64, z 112 in $SECONDARY_OVERWORLD"), message)
        // 112 + 8192 and 112 − 4096.
        assertTrue(message.contains("moved that respawn point to x 8304, y 64, z -3984"), message)
        assertEquals(before, deployment.contents())
    }

    @Test
    fun `a respawn point that had no bed before the merge is counted rather than refused`() {
        // Entirely ordinary on a live server: vanilla keeps a respawn point after
        // its bed is broken and tells its owner when they die. The merge has
        // taken nothing from them, so it says so instead of refusing.
        deployment.withRealSecondaryChunks()
        deployment.playerSave(
            ALICE,
            save(SECONDARY_OVERWORLD) { put("respawn", globalPos(SECONDARY_OVERWORLD, 2, 64, 3)) },
        )

        val report = merge()

        assertEquals(RespawnCheckReport(confirmed = 0, alreadyWithoutABed = 1), report.section<RespawnCheckReport>())
        assertEquals(
            listOf("respawn points moved     : 1 — 0 confirmed against the relocated chunks, " +
                "1 had no bed before the merge either"),
            report.section<RespawnCheckReport>().lines(),
        )
    }

    @Test
    fun `looking for the bed leaves Secondary's own chunk data byte for byte as it was`() {
        // The check reads both sides, and one of those sides is the live save
        // that the pre-merge backup is a rollback of.
        deployment.withRealSecondaryChunks(withABedAt(BlockPos(2, 64, 3)))
        deployment.playerSave(
            ALICE,
            save(SECONDARY_OVERWORLD) { put("respawn", globalPos(SECONDARY_OVERWORLD, 2, 64, 3)) },
        )
        val before = secondaryChunkData()

        merge()

        assertEquals(before, secondaryChunkData())
    }

    @Test
    fun `a respawn point in Primary is never asked about, because nothing moved it`() {
        // Primary's chunk data is unreadable nonsense in this fixture and the
        // merge never opens it. A check that asked about a Primary bed would
        // fail here, and that is exactly the point of it not asking.
        deployment.withRealSecondaryChunks()
        deployment.playerSave(
            ALICE,
            save(SECONDARY_OVERWORLD) { put("respawn", globalPos("minecraft:overworld", 2, 64, 3)) },
        )

        val report = merge()

        assertEquals(RespawnCheckReport(confirmed = 0, alreadyWithoutABed = 0), report.section<RespawnCheckReport>())
    }

    // ---- fixtures ------------------------------------------------------------

    /** Secondary's overworld with a bed standing in the chunk at the origin. */
    private fun withABedAt(bed: BlockPos) = listOf(
        SyntheticChunks.Chunk(0, 0, beds = listOf(bed)),
        SyntheticChunks.Chunk(5, 3),
        SyntheticChunks.Chunk(32, 0),
        MergedDeploymentFixture.FRONTIER,
    )

    /** Secondary's overworld with the bed in the half-generated chunk that stays behind. */
    private fun withABedInTheFrontier(bed: BlockPos) = listOf(
        SyntheticChunks.Chunk(0, 0),
        SyntheticChunks.Chunk(5, 3),
        SyntheticChunks.Chunk(32, 0),
        SyntheticChunks.Chunk(7, 7, SyntheticChunks.PROTO, beds = listOf(bed)),
    )

    /** A player save as a 26.2 server writes it, with the tags every save carries. */
    private fun save(
        dimension: String,
        x: Double = 1.0,
        y: Double = 64.0,
        z: Double = 2.0,
        extras: CompoundTag.() -> Unit = {},
    ) = CompoundTag().apply {
        putString("Dimension", dimension)
        put("Pos", vec3(x, y, z))
        put(
            "Rotation",
            ListTag().apply {
                add(FloatTag.valueOf(0f))
                add(FloatTag.valueOf(0f))
            },
        )
        putInt("DataVersion", 4536)
        extras()
    }

    private fun vec3(x: Double, y: Double, z: Double) = ListTag().apply {
        add(DoubleTag.valueOf(x))
        add(DoubleTag.valueOf(y))
        add(DoubleTag.valueOf(z))
    }

    private fun globalPos(dimension: String, x: Int, y: Int, z: Int) = CompoundTag().apply {
        putString("dimension", dimension)
        putIntArray("pos", intArrayOf(x, y, z))
    }

    private fun compass(dimension: String, x: Int, y: Int, z: Int) = CompoundTag().apply {
        putString("id", "minecraft:compass")
        put(
            "components",
            CompoundTag().apply {
                put(
                    "minecraft:lodestone_tracker",
                    CompoundTag().apply {
                        put("target", globalPos(dimension, x, y, z))
                        putBoolean("tracked", true)
                    },
                )
            },
        )
    }

    private fun positionIn(tag: CompoundTag): List<Double> =
        tag.getListOrEmpty("Pos").let { pos -> (0 until pos.size).map { pos.getDoubleOr(it, Double.NaN) } }

    /** Every one of Secondary's chunk files, by path and content digest. */
    private fun secondaryChunkData(): Map<String, String> {
        val found = sortedMapOf<String, String>()
        for (role in DimensionRole.entries) {
            val storage = Footprint.storageFolder(deployment.levelDir, deployment.secondaryDimension(role))
            if (!Files.isDirectory(storage)) continue
            Files.walk(storage).use { paths ->
                paths.filter(Files::isRegularFile).forEach { file ->
                    found[deployment.levelDir.relativize(file).toString()] =
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") {
                            "%02x".format(it)
                        }
                }
            }
        }
        return found
    }

    /** The Per-World Bucket a player banked in Secondary's overworld, before the merge moves it. */
    private val secondaryBase =
        """{"dimension":"overworld","x":10.5,"y":70.0,"z":20.5,"yaw":0.0,"pitch":0.0}"""

    /** A nest of Regions in Secondary's End, and one in Primary that must survive it. */
    private val endRegionFile = """
        {
          "regions": {
            "0": {
              "title": "Ender Outpost",
              "start-x": 0,
              "start-z": 0,
              "end-x": 32,
              "end-z": 32,
              "world": "last_the_end",
              "members": [
                "$ALICE",
                "$BOB"
              ],
              "sub-regions": {
                "0": {
                  "title": "Ender Cellar",
                  "start-x": 4,
                  "start-z": 4,
                  "end-x": 8,
                  "end-z": 8,
                  "world": "last_the_end",
                  "members": []
                }
              }
            },
            "1": {
              "title": "Home",
              "start-x": 100,
              "start-z": 100,
              "end-x": 120,
              "end-z": 120,
              "world": "world",
              "members": []
            }
          }
        }
    """.trimIndent()

    /** An Embassy plot whose anchor still sends visitors into Secondary's End. */
    private val endDestinationFile = """
        {
          "regions": {
            "0": {
              "title": "Ambassador Plot",
              "start-x": 1000,
              "start-z": 1000,
              "end-x": 1010,
              "end-z": 1010,
              "world": "embassies",
              "members": [],
              "flags": [
                "EMBASSY"
              ],
              "metadata": {
                "embassy-destination": {
                  "x": 8.5,
                  "y": 64.0,
                  "z": 8.5,
                  "yaw": 0.0,
                  "pitch": 0.0,
                  "world": "last_the_end"
                }
              }
            }
          }
        }
    """.trimIndent()
}
