package eu.mctraveler.region

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Unit tier for the Region service's pure logic: legacy-format storage
 * round-trip, containment (deepest recursive match), full-intersection overlap
 * detection, and the locate search. Command flows are gametests.
 *
 * The on-disk format is a public contract — `regions.json` as the Portal's
 * `JSON.stringify(…, null, 2)` wrote it, shared with the pre-proxy plugin and
 * the importer — so storage tests assert raw file text.
 */
class RegionServiceTest {
    @TempDir
    lateinit var dir: Path

    private val alice = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val bob = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun file(): Path = dir.resolve("regions.json")

    private fun service(): RegionService = RegionService(file())

    private fun serviceWith(text: String): RegionService {
        Files.writeString(file(), text)
        return service()
    }

    // A regions.json exactly as the Portal wrote it: y bounds omitted when they
    // are the 320/−64 defaults, present otherwise (and after `members`, where
    // the Portal's serializer put them), flags and sub-regions only when
    // non-empty, 2-space pretty-printing.
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
              ],
              "flags": [
                "EMBASSY"
              ],
              "sub-regions": {
                "0": {
                  "title": "Inner Sanctum",
                  "start-x": 0,
                  "start-z": 0,
                  "end-x": 10,
                  "end-z": 10,
                  "world": "world",
                  "members": [
                    "22222222-2222-2222-2222-222222222222"
                  ],
                  "start-y": 255,
                  "end-y": 15
                }
              }
            },
            "1": {
              "title": "Far Keep",
              "start-x": 1000,
              "start-z": 1000,
              "end-x": 1050,
              "end-z": 1080,
              "world": "last_nether",
              "members": [
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222"
              ]
            }
          }
        }
    """.trimIndent()

    // ---- storage ----

    @Test
    fun `no regions file means no regions`() {
        assertTrue(service().roots.isEmpty())
    }

    @Test
    fun `legacy file loads with omitted y bounds defaulting to 320 and -64`() {
        val roots = serviceWith(legacyFile).roots
        assertEquals(listOf("Spawn Commons", "Far Keep"), roots.map { it.title })

        val commons = roots[0]
        assertEquals(320, commons.startY)
        assertEquals(-64, commons.endY)
        assertEquals("world", commons.world)
        assertEquals(setOf(alice), commons.members)
        assertEquals(setOf("EMBASSY"), commons.flags)

        val sanctum = commons.subRegions.single()
        assertEquals("Inner Sanctum", sanctum.title)
        assertEquals(255, sanctum.startY)
        assertEquals(15, sanctum.endY)
        assertSame(commons, sanctum.parent)

        val keep = roots[1]
        assertEquals("last_nether", keep.world)
        assertEquals(setOf(alice, bob), keep.members)
        assertTrue(keep.flags.isEmpty())
        assertTrue(keep.subRegions.isEmpty())
    }

    @Test
    fun `saving reproduces the legacy file byte for byte`() {
        val service = serviceWith(legacyFile)
        service.save()
        assertEquals(legacyFile, Files.readString(file()))
    }

    @Test
    fun `a new region saves with default y bounds omitted and no flags key`() {
        val service = service()
        val region = Region(
            title = "Alice's Place",
            world = "world",
            startX = 3, startZ = 4, endX = 12, endZ = 14,
        )
        region.members.add(alice)
        service.add(region, parent = null)

        val expected = """
            {
              "regions": {
                "0": {
                  "title": "Alice's Place",
                  "start-x": 3,
                  "start-z": 4,
                  "end-x": 12,
                  "end-z": 14,
                  "world": "world",
                  "members": [
                    "11111111-1111-1111-1111-111111111111"
                  ]
                }
              }
            }
        """.trimIndent()
        assertEquals(expected, Files.readString(file()))
    }

    // ---- metadata (deviation 6) ----

    // The same schema with the one new optional key: an embassy region as
    // /embassy create writes it. `metadata` sits after `flags`, and the two
    // never meet `sub-regions` in practice — a region cannot be created inside
    // an embassy, so an embassy never has one.
    private val embassyFile = """
        {
          "regions": {
            "0": {
              "title": "Unnamed Embassy",
              "start-x": 3,
              "start-z": 3,
              "end-x": 13,
              "end-z": 13,
              "world": "embassies",
              "members": [
                "11111111-1111-1111-1111-111111111111"
              ],
              "flags": [
                "EMBASSY"
              ],
              "metadata": {
                "embassy-destination": {
                  "x": 123.5,
                  "y": 64.0,
                  "z": -87.25,
                  "yaw": 90.0,
                  "pitch": 0.0,
                  "world": "world"
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `a region with no metadata writes no metadata key`() {
        // The whole of deviation 6's promise: legacy entries are untouched.
        val service = serviceWith(legacyFile)
        assertTrue(service.roots.all { it.metadata.isEmpty() })
        service.save()
        assertEquals(legacyFile, Files.readString(file()))
    }

    @Test
    fun `metadata loads as a json tree keyed in file order`() {
        val embassy = serviceWith(embassyFile).roots.single()
        val destination = embassy.metadata.getValue("embassy-destination").asJsonObject
        assertEquals(123.5, destination.get("x").asDouble)
        assertEquals(-87.25, destination.get("z").asDouble)
        assertEquals(90.0f, destination.get("yaw").asFloat)
        assertEquals("world", destination.get("world").asString)
        assertEquals(
            listOf("x", "y", "z", "yaw", "pitch", "world"),
            destination.keySet().toList(),
        )
    }

    @Test
    fun `saving reproduces a metadata file byte for byte`() {
        // Including the number literals: "64.0" must not come back as "64".
        val service = serviceWith(embassyFile)
        service.save()
        assertEquals(embassyFile, Files.readString(file()))
    }

    @Test
    fun `metadata built in memory writes the embassy-destination shape`() {
        // Pins the bytes /embassy create produces — the format ticket 05's
        // importer has to write for the twenty Nucleus-era embassies.
        val service = service()
        val region = Region(
            title = "Unnamed Embassy",
            world = "embassies",
            startX = 3, startZ = 3, endX = 13, endZ = 13,
        )
        region.members.add(alice)
        region.flags.add("EMBASSY")
        region.metadata["embassy-destination"] = JsonObject().apply {
            addProperty("x", 123.5)
            addProperty("y", 64.0)
            addProperty("z", -87.25)
            addProperty("yaw", 90.0f)
            addProperty("pitch", 0.0f)
            addProperty("world", "world")
        }
        service.add(region, parent = null)

        assertEquals(embassyFile, Files.readString(file()))
    }

    @Test
    fun `adding a sub-region wires the parent and nests it in the file`() {
        val service = serviceWith(legacyFile)
        val sub = Region(
            title = "Annex",
            world = "world",
            startX = 20, startZ = 20, endX = 30, endZ = 30,
        )
        sub.members.add(bob)
        service.add(sub, parent = service.roots[0])

        assertSame(service.roots[0], sub.parent)
        assertTrue(Files.readString(file()).contains("\"Annex\""))
        // And it comes back on reload, still nested.
        val reloaded = service()
        assertEquals(
            listOf("Inner Sanctum", "Annex"),
            reloaded.roots[0].subRegions.map { it.title },
        )
    }

    @Test
    fun `removing a sub-region detaches it from its parent`() {
        val service = serviceWith(legacyFile)
        val sanctum = service.roots[0].subRegions.single()
        service.remove(sanctum)
        assertTrue(service.roots[0].subRegions.isEmpty())
        assertTrue(service().roots[0].subRegions.isEmpty())
    }

    @Test
    fun `removing a root region drops it from the file`() {
        val service = serviceWith(legacyFile)
        service.remove(service.roots[1])
        assertEquals(listOf("Spawn Commons"), service().roots.map { it.title })
    }

    @Test
    fun `a malformed regions file throws instead of being silently replaced`() {
        Files.writeString(file(), "{ not json")
        assertThrows(Exception::class.java) { service() }
    }

    // ---- containment ----

    @Test
    fun `containment is inclusive and normalises swapped corners`() {
        // Corners deliberately swapped: start > end on both axes.
        val region = Region("R", "world", startX = 10, startZ = 8, endX = -5, endZ = -3)
        assertTrue(region.contains(-5, 0, -3))
        assertTrue(region.contains(10, 0, 8))
        assertTrue(region.contains(0, 320, 0))
        assertTrue(region.contains(0, -64, 0))
        assertTrue(!region.contains(11, 0, 0))
        assertTrue(!region.contains(0, 0, 9))
    }

    @Test
    fun `containment respects y bounds`() {
        val region = Region("R", "world", 0, 0, 10, 10, startY = 100, endY = 50)
        assertTrue(region.contains(5, 50, 5))
        assertTrue(region.contains(5, 100, 5))
        assertTrue(!region.contains(5, 49, 5))
        assertTrue(!region.contains(5, 101, 5))
    }

    @Test
    fun `lookup finds the deepest matching sub-region`() {
        val service = serviceWith(legacyFile)
        val commons = service.roots[0]
        val sanctum = commons.subRegions.single()
        // Inside the sub-region's column and y range: deepest match wins.
        assertSame(sanctum, service.regionAt("world", 5, 64, 5))
        // Inside the parent but outside the sub-region's y range.
        assertSame(commons, service.regionAt("world", 5, 300, 5))
        // Inside the parent, outside the sub-region's column.
        assertSame(commons, service.regionAt("world", -10, 64, -10))
        // Outside everything.
        assertNull(service.regionAt("world", 500, 64, 500))
    }

    @Test
    fun `lookup is scoped to the region's World`() {
        val service = serviceWith(legacyFile)
        assertNull(service.regionAt("world_nether", 5, 64, 5))
        assertSame(service.roots[1], service.regionAt("last_nether", 1025, 64, 1040))
    }

    // ---- overlap (full intersection — deviation 3) ----

    @Test
    fun `a corner-sharing rectangle overlaps`() {
        val service = serviceWith(legacyFile)
        assertSame(service.roots[0], service.firstIntersecting("world", 40, 100, 60, 100))
    }

    @Test
    fun `a thin strip crossing a region overlaps even with no corners inside`() {
        val service = serviceWith(legacyFile)
        // Spans x −100..100 at z 0..1: crosses Spawn Commons (−20..40 × −20..60)
        // while every corner of both rectangles is outside the other — the
        // Portal's corner-only check missed exactly this.
        assertSame(service.roots[0], service.firstIntersecting("world", -100, 100, 0, 1))
    }

    @Test
    fun `a rectangle fully containing a region overlaps`() {
        val service = serviceWith(legacyFile)
        assertSame(service.roots[0], service.firstIntersecting("world", -100, 100, -100, 100))
    }

    @Test
    fun `overlap detection recurses into sub-regions`() {
        val service = serviceWith(legacyFile)
        val sanctum = service.roots[0].subRegions.single()
        // Intersects only the sub-region's column when its parent is excluded.
        assertSame(
            sanctum,
            service.firstIntersecting("world", 5, 8, 5, 8, excluding = service.roots[0]),
        )
    }

    @Test
    fun `overlap detection is scoped to the World`() {
        val service = serviceWith(legacyFile)
        assertNull(service.firstIntersecting("world_the_end", -100, 100, -100, 100))
    }

    @Test
    fun `excluding a prospective parent also excludes its ancestors`() {
        val service = serviceWith(legacyFile)
        val sanctum = service.roots[0].subRegions.single()
        // A rect inside Inner Sanctum: both Sanctum and Spawn Commons intersect
        // it, but both are the ancestor chain of the region being created.
        assertNull(service.firstIntersecting("world", 2, 4, 2, 4, excluding = sanctum))
    }

    @Test
    fun `non-overlapping rectangles do not collide`() {
        val service = serviceWith(legacyFile)
        assertNull(service.firstIntersecting("world", 41, 60, 61, 80))
    }

    // ---- locate search ----

    @Test
    fun `search matches region titles case-insensitively by substring`() {
        val service = serviceWith(legacyFile)
        assertEquals(listOf("Spawn Commons"), service.search("spawn") { null }.map { it.title })
    }

    @Test
    fun `search matches member names and recurses into sub-regions`() {
        val service = serviceWith(legacyFile)
        val names = mapOf(alice to "Alice", bob to "BobTheBuilder")
        val found = service.search("bobthe") { names[it] }
        // Bob is a member of Inner Sanctum (sub-region) and Far Keep.
        assertEquals(listOf("Inner Sanctum", "Far Keep"), found.map { it.title })
    }

    @Test
    fun `search with no matches finds nothing`() {
        val service = serviceWith(legacyFile)
        assertTrue(service.search("zzz") { null }.isEmpty())
    }
}
