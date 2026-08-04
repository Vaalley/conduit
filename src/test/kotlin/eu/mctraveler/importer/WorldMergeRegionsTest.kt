package eu.mctraveler.importer

import eu.mctraveler.embassy.EmbassyDestination
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionService
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A player who owned a Region in Secondary still owns it, protecting the same
 * build, after the merge (merge spec, User Stories 21–24).
 *
 * Driven through the whole merge command against a synthetic run directory —
 * chunk relocation and all — so what is asserted is the `regions.json` a server
 * would boot from rather than the shape of any intermediate step, and so a
 * refusal here is asserted against a merge that had already staged real chunk
 * data and had to throw all of it away. [MergedDeploymentFixture]'s geography
 * puts the landmass at x +8192, z +0 — an eighth of that in the nether — and
 * every coordinate below is arrived at by hand from those two numbers.
 */
class WorldMergeRegionsTest {
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

    private lateinit var save: MergedDeploymentFixture

    @BeforeEach
    fun buildDeployment() {
        // Real chunk data, because the merge relocates before it sweeps and the
        // relocation is never stubbed (merge spec, "Testing Decisions").
        save = MergedDeploymentFixture(dir).build().withRealSecondaryChunks()
    }

    /**
     * [acceptEndLoss] is only ever passed by the tests that put something in
     * Secondary's End: the End gate refuses the whole merge over it otherwise,
     * and what this suite is about is what the Regions sweep did before that
     * gate ran.
     */
    private fun merge(acceptEndLoss: Boolean = false) =
        WorldMerge(save.plan(planOnly = false, acceptEndLoss = acceptEndLoss)).run()

    /** The swept file as the running server reads it back. */
    private fun swept(): List<Region> = RegionService(save.regionsFile).roots

    private fun region(title: String): Region {
        fun find(regions: List<Region>): Region? = regions.firstNotNullOfOrNull {
            if (it.title == title) it else find(it.subRegions)
        }
        return requireNotNull(find(swept())) { "no region titled \"$title\" survived the merge" }
    }

    // A regions.json exactly as the Portal's serializer wrote it, holding one of
    // everything the sweep has to tell apart: a Region already in Primary, a
    // Secondary Region with a sub-region and non-default y bounds, a Secondary
    // nether Region whose corners were captured in the un-normalised order, and
    // an Embassy whose saved destination names Secondary's overworld.
    private val legacyFile = """
        {
          "regions": {
            "0": {
              "title": "Spawn Commons",
              "start-x": -20,
              "start-z": -20,
              "end-x": 40,
              "end-z": 60,
              "world": "world",
              "members": [
                "11111111-1111-1111-1111-111111111111"
              ]
            },
            "1": {
              "title": "Harbour",
              "start-x": 100,
              "start-z": 200,
              "end-x": 300,
              "end-z": 400,
              "world": "last",
              "members": [
                "22222222-2222-2222-2222-222222222222"
              ],
              "start-y": 200,
              "end-y": 40,
              "sub-regions": {
                "0": {
                  "title": "Harbour Vault",
                  "start-x": 150,
                  "start-z": 250,
                  "end-x": 160,
                  "end-z": 260,
                  "world": "last",
                  "members": [
                    "22222222-2222-2222-2222-222222222222"
                  ]
                }
              }
            },
            "2": {
              "title": "Fortress Road",
              "start-x": 16,
              "start-z": -32,
              "end-x": -48,
              "end-z": 64,
              "world": "last_nether",
              "members": []
            },
            "3": {
              "title": "Ambassador Plot",
              "start-x": 1000,
              "start-z": 1000,
              "end-x": 1010,
              "end-z": 1010,
              "world": "embassies",
              "members": [
                "11111111-1111-1111-1111-111111111111"
              ],
              "flags": [
                "EMBASSY"
              ],
              "metadata": {
                "embassy-destination": {
                  "x": 128.5,
                  "y": 64.0,
                  "z": -256.5,
                  "yaw": 90.0,
                  "pitch": 0.0,
                  "world": "last"
                }
              }
            }
          }
        }
    """.trimIndent()

    // ---- the file the server boots from -------------------------------------

    @Test
    fun `the swept file is the legacy file with Secondary moved and nothing else touched`() {
        save.withRegions(legacyFile)

        merge()

        assertEquals(
            """
            {
              "regions": {
                "0": {
                  "title": "Spawn Commons",
                  "start-x": -20,
                  "start-z": -20,
                  "end-x": 40,
                  "end-z": 60,
                  "world": "world",
                  "members": [
                    "11111111-1111-1111-1111-111111111111"
                  ]
                },
                "1": {
                  "title": "Harbour",
                  "start-x": 8292,
                  "start-z": 200,
                  "end-x": 8492,
                  "end-z": 400,
                  "world": "world",
                  "members": [
                    "22222222-2222-2222-2222-222222222222"
                  ],
                  "start-y": 200,
                  "end-y": 40,
                  "sub-regions": {
                    "0": {
                      "title": "Harbour Vault",
                      "start-x": 8342,
                      "start-z": 250,
                      "end-x": 8352,
                      "end-z": 260,
                      "world": "world",
                      "members": [
                        "22222222-2222-2222-2222-222222222222"
                      ]
                    }
                  }
                },
                "2": {
                  "title": "Fortress Road",
                  "start-x": 1040,
                  "start-z": -32,
                  "end-x": 976,
                  "end-z": 64,
                  "world": "world_nether",
                  "members": []
                },
                "3": {
                  "title": "Ambassador Plot",
                  "start-x": 1000,
                  "start-z": 1000,
                  "end-x": 1010,
                  "end-z": 1010,
                  "world": "embassies",
                  "members": [
                    "11111111-1111-1111-1111-111111111111"
                  ],
                  "flags": [
                    "EMBASSY"
                  ],
                  "metadata": {
                    "embassy-destination": {
                      "x": 8320.5,
                      "y": 64.0,
                      "z": -256.5,
                      "yaw": 90.0,
                      "pitch": 0.0,
                      "world": "world"
                    }
                  }
                }
              }
            }
            """.trimIndent(),
            save.regionsJson(),
        )
    }

    @Test
    fun `a Region already in Primary comes back byte for byte`() {
        save.withRegions(legacyFile)

        merge()

        // The bytes the Portal wrote, still exactly as it wrote them — indentation,
        // key order and all — inside a file the merge otherwise rewrote.
        assertTrue(
            save.regionsJson().startsWith(
                """
                {
                  "regions": {
                    "0": {
                      "title": "Spawn Commons",
                      "start-x": -20,
                      "start-z": -20,
                      "end-x": 40,
                      "end-z": 60,
                      "world": "world",
                      "members": [
                        "11111111-1111-1111-1111-111111111111"
                      ]
                    },
                """.trimIndent(),
            ),
            "Primary's own Region was re-serialised differently:\n${save.regionsJson()}",
        )
    }

    @Test
    fun `a file with nothing in Secondary is left exactly as it was, down to the last byte`() {
        // The trailing newline is the point: the merge is not the thing that
        // normalises a file it has no change to make to, so a save whose Regions
        // were all in Primary reads afterwards exactly as it read before.
        save.withRegions(primaryOnlyFile)

        val report = merge()

        assertEquals(primaryOnlyFile, save.regionsJson())
        assertEquals(0, report.regions.movedCount)
        assertFalse(report.regions.rewroteFile)
    }

    // ---- where the Regions land ---------------------------------------------

    @Test
    fun `a Region in Secondary's overworld arrives on Primary's, moved by the whole offset`() {
        save.withRegions(legacyFile)

        merge()

        val harbour = region("Harbour")
        assertEquals("world", harbour.world)
        assertEquals(8292, harbour.startX)
        assertEquals(8492, harbour.endX)
        assertEquals(200, harbour.startZ)
        assertEquals(400, harbour.endZ)
    }

    @Test
    fun `a Region in Secondary's nether moves one eighth as far, so it still surrounds its portal`() {
        save.withRegions(legacyFile)

        merge()

        val road = region("Fortress Road")
        assertEquals("world_nether", road.world)
        // The corners keep the un-normalised order the creator captured them in.
        assertEquals(1040, road.startX)
        assertEquals(976, road.endX)
        assertEquals(-32, road.startZ)
        assertEquals(64, road.endZ)
    }

    @Test
    fun `a sub-region moves with its parent and is still nested inside it`() {
        save.withRegions(legacyFile)

        merge()

        val harbour = region("Harbour")
        val vault = harbour.subRegions.single()
        assertEquals("Harbour Vault", vault.title)
        assertEquals("world", vault.world)
        assertEquals(8342, vault.startX)
        assertEquals(8352, vault.endX)
        assertTrue(
            vault.minX >= harbour.minX && vault.maxX <= harbour.maxX &&
                vault.minZ >= harbour.minZ && vault.maxZ <= harbour.maxZ,
            "the sub-region no longer lies inside its parent",
        )
    }

    @Test
    fun `a nest moves whole, however deep it goes`() {
        save.withRegions(nestedFile)

        val report = merge()

        // One offset for the whole nest, so what was layered is still layered.
        assertEquals(listOf(8192, 8292, 8392), listOf("Keep", "Bailey", "Cellar").map { region(it).startX })
        assertTrue(swept().single().subRegions.single().subRegions.single().title == "Cellar")
        assertEquals(3, report.regions.movedCount)
    }

    @Test
    fun `vertical bounds are never changed`() {
        save.withRegions(legacyFile)

        merge()

        assertEquals(200, region("Harbour").startY)
        assertEquals(40, region("Harbour").endY)
        assertEquals(Region.DEFAULT_START_Y, region("Fortress Road").startY)
        assertEquals(Region.DEFAULT_END_Y, region("Fortress Road").endY)
    }

    @Test
    fun `members and flags come along untouched`() {
        save.withRegions(legacyFile)

        merge()

        assertEquals(
            setOf("22222222-2222-2222-2222-222222222222"),
            region("Harbour").members.map { it.toString() }.toSet(),
        )
        assertEquals(setOf(Region.EMBASSY_FLAG), region("Ambassador Plot").flags)
    }

    // ---- the Embassies -------------------------------------------------------

    @Test
    fun `an Embassy destination naming Secondary sends visitors to the same build on Primary`() {
        save.withRegions(legacyFile)

        merge()

        val destination = EmbassyDestination.of(region("Ambassador Plot"))
        assertEquals(
            EmbassyDestination(world = "world", x = 8320.5, y = 64.0, z = -256.5, yaw = 90.0f, pitch = 0.0f),
            destination,
        )
    }

    @Test
    fun `the Embassy's own Region does not move, because the Embassies are in no World`() {
        save.withRegions(legacyFile)

        merge()

        val plot = region("Ambassador Plot")
        assertEquals("embassies", plot.world)
        assertEquals(1000, plot.startX)
        assertEquals(1010, plot.endX)
    }

    @Test
    fun `a destination the merge cannot read is refused by name, and nothing is written`() {
        save.withRegions(withDestination("""{ "world": "last" }"""))
        val before = save.contents()

        val refusal = assertThrows { merge() }

        assertTrue(
            refusal.message!!.startsWith(
                "the Region \"Ambassador Plot\" carries an \"embassy-destination\" the merge cannot read",
            ),
            "unexpected refusal: ${refusal.message}",
        )
        assertEquals(before.keys, save.contents().keys)
    }

    // ---- the overlap the merge must refuse over ------------------------------

    @Test
    fun `a relocated Region landing on a Primary one refuses, naming both`() {
        save.withRegions(overlappingFile)

        val refusal = assertThrows { merge() }

        assertEquals(
            "Secondary's Region \"Harbour\" lands on x 8292…8492, z 200…400 in \"world\", where " +
                "Primary's Region \"Old Quarry\" already covers x 8400…8600, z 300…500 — two owners " +
                "cannot share a cuboid, so choose another offset, or move one of the two before merging",
            refusal.message,
        )
    }

    @Test
    fun `the overlap refusal leaves the run directory exactly as it was`() {
        save.withRegions(overlappingFile)
        val before = save.contents()

        assertThrows { merge() }

        assertEquals(before, save.contents())
        assertEquals(overlappingFile, save.regionsJson())
    }

    // ---- Secondary's End, which this sweep only reports ----------------------

    @Test
    fun `a Region in Secondary's End is left where it is and named for the End gate`() {
        save.withRegions(endFile)

        val report = merge(acceptEndLoss = true)

        // This sweep neither moves it nor rewrites the file over it — it has no
        // offset for a dimension being discarded, so it leaves it and says so.
        // What becomes of it afterwards belongs to the End gate, which deletes it
        // once the operator has accepted the loss (WorldMergeEndGateTest); the
        // opt-in is here only because without it that gate stops the whole merge.
        assertEquals(0, report.regions.movedCount)
        assertFalse(report.regions.rewroteFile)
        assertTrue(
            report.regions.endAnchored.contains("the Region \"Ender Outpost\""),
            "the End Region was not reported: ${report.regions.endAnchored}",
        )
    }

    @Test
    fun `an Embassy destination naming Secondary's End is left alone and named too`() {
        save.withRegions(endFile)

        val report = merge(acceptEndLoss = true)

        assertEquals(0, report.regions.destinationsRewritten)
        assertFalse(report.regions.rewroteFile)
        assertTrue(
            report.regions.endAnchored.contains(
                "the destination of the Embassy Region \"Ambassador Plot\"",
            ),
            "the End destination was not reported: ${report.regions.endAnchored}",
        )
    }

    // ---- what the operator reads --------------------------------------------

    @Test
    fun `the report states how many Regions moved, in which dimensions, and how many destinations`() {
        save.withRegions(legacyFile)

        val report = merge()

        assertEquals(
            listOf(
                "Regions moved            : 3 — overworld 2, nether 1",
                "Regions left alone       : 2",
                "Embassy destinations     : 1 moved to Primary",
                "regions.json             : rewritten",
            ),
            report.regions.lines(),
        )
    }

    @Test
    fun `the report names everything still anchored in Secondary's End`() {
        save.withRegions(endFile)

        val report = merge(acceptEndLoss = true)

        assertEquals(
            listOf(
                "Regions moved            : none",
                "Regions left alone       : 2",
                "Embassy destinations     : none moved",
                "regions.json             : left exactly as it was",
                "still in Secondary's End : the Region \"Ender Outpost\"",
                "still in Secondary's End : the destination of the Embassy Region \"Ambassador Plot\"",
            ),
            report.regions.lines(),
        )
    }

    @Test
    fun `the placement still leads the report, because it is what the operator has to accept`() {
        save.withRegions(legacyFile)

        val report = merge()

        val lines = report.lines()
        assertEquals(report.placement.lines(), lines.take(report.placement.lines().size))
        // And this sweep's own section follows it whole, wherever the phases that
        // run before and after it put theirs.
        assertTrue(
            Collections.indexOfSubList(lines, report.regions.lines()) >= report.placement.lines().size,
            "the Regions section is not in the report intact:\n${lines.joinToString("\n")}",
        )
        assertEquals(MergeOffset(8192, 0), report.placement.offset)
    }

    @Test
    fun `the merge leaves no staging directory behind`() {
        save.withRegions(legacyFile)

        merge()

        assertTrue(
            save.contents().keys.none { it.startsWith(WorldMerge.STAGING_DIRECTORY) },
            "the merge left staged files behind: ${save.contents().keys}",
        )
        assertTrue(Files.notExists(save.staging), "a later run would refuse over ${save.staging}")
    }

    // ---- fixtures ------------------------------------------------------------

    private fun assertThrows(run: () -> Unit): MigrationRefused =
        org.junit.jupiter.api.Assertions.assertThrows(MigrationRefused::class.java, run)

    /** [legacyFile]'s Embassy alone, carrying [destination] as its saved destination. */
    private fun withDestination(destination: String): String = """
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
                "embassy-destination": $destination
              }
            }
          }
        }
    """.trimIndent()

    /**
     * A save whose Regions are all already in Primary — and which ends in a
     * newline, as a hand-edited one might, so a test can tell "left alone" from
     * "rewritten to the same thing".
     */
    private val primaryOnlyFile = """
        {
          "regions": {
            "0": {
              "title": "Spawn Commons",
              "start-x": -20,
              "start-z": -20,
              "end-x": 40,
              "end-z": 60,
              "world": "world",
              "members": [
                "11111111-1111-1111-1111-111111111111"
              ]
            }
          }
        }
    """.trimIndent() + "\n"

    /** A Secondary nest three deep, each Region inside the one above it. */
    private val nestedFile = """
        {
          "regions": {
            "0": {
              "title": "Keep",
              "start-x": 0,
              "start-z": 0,
              "end-x": 600,
              "end-z": 600,
              "world": "last",
              "members": [],
              "sub-regions": {
                "0": {
                  "title": "Bailey",
                  "start-x": 100,
                  "start-z": 100,
                  "end-x": 500,
                  "end-z": 500,
                  "world": "last",
                  "members": [],
                  "sub-regions": {
                    "0": {
                      "title": "Cellar",
                      "start-x": 200,
                      "start-z": 200,
                      "end-x": 400,
                      "end-z": 400,
                      "world": "last",
                      "members": []
                    }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    /** A Primary Region sitting exactly where Secondary's "Harbour" would land. */
    private val overlappingFile = """
        {
          "regions": {
            "0": {
              "title": "Old Quarry",
              "start-x": 8400,
              "start-z": 300,
              "end-x": 8600,
              "end-z": 500,
              "world": "world",
              "members": []
            },
            "1": {
              "title": "Harbour",
              "start-x": 100,
              "start-z": 200,
              "end-x": 300,
              "end-z": 400,
              "world": "last",
              "members": []
            }
          }
        }
    """.trimIndent()

    /** Everything this sweep leaves for the End gate: a Region there, and a destination naming it. */
    private val endFile = """
        {
          "regions": {
            "0": {
              "title": "Ender Outpost",
              "start-x": 0,
              "start-z": 0,
              "end-x": 32,
              "end-z": 32,
              "world": "last_the_end",
              "members": []
            },
            "1": {
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
