package eu.mctraveler.importer

import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionWorlds
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The Nucleus-era region file: a different serializer's rendering of the same
 * idea, read into the live store's model without losing anything an embassy
 * needs (spec User Story 38).
 */
class NucleusRegionsTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private fun embassies(text: String = NucleusDeploymentFixture.NUCLEUS_REGIONS): List<Region> =
        NucleusRegions.regionsIn(text, RegionWorlds.EMBASSIES)

    @Test
    fun `only the embassies world's regions are taken`() {
        assertEquals(listOf("Jam's Embassy", "Nomad's Embassy"), embassies().map { it.title })
    }

    @Test
    fun `corners, members and flags cross over`() {
        val embassy = embassies().first()

        assertEquals(RegionWorlds.EMBASSIES, embassy.world)
        assertEquals(listOf(3, 3, 13, 13), listOf(embassy.startX, embassy.startZ, embassy.endX, embassy.endZ))
        assertEquals(listOf(NucleusDeploymentFixture.JAM), embassy.members.toList())
        assertEquals(listOf("EMBASSY"), embassy.flags.toList())
    }

    @Test
    fun `Nucleus's full-height bounds are the live store's defaults`() {
        val embassy = embassies().first()

        assertEquals(Region.DEFAULT_START_Y, embassy.startY)
        assertEquals(Region.DEFAULT_END_Y, embassy.endY)
    }

    @Test
    fun `a point that omitted its y keeps the height Nucleus read it at`() {
        val boundless = """
            [{"title":"Old Embassy","start":{"x":3,"z":3},"end":{"x":13,"z":13},
              "world":"embassies","members":[],"regions":[]}]
        """.trimIndent()

        val embassy = embassies(boundless).single()

        // Nucleus's own RegionData.toRegion: the world's build height, and 15.
        assertEquals(320, embassy.startY)
        assertEquals(15, embassy.endY)
    }

    @Test
    fun `the destination is carried verbatim, keys and number literals alike`() {
        val destination = embassies().first().metadata.getValue("embassy-destination").asJsonObject

        assertEquals(listOf("x", "y", "z", "yaw", "pitch", "world"), destination.entrySet().map { it.key })
        // The literal, not a re-rendered double: 64.0 must not come back as 64.
        assertEquals("64.0", destination.get("y").asString)
        assertEquals("0.0", destination.get("pitch").asString)
        assertEquals("world", destination.get("world").asString)
    }

    @Test
    fun `sub-regions come along, wired to their parent`() {
        val nested = """
            [{"title":"Big Embassy","start":{"x":3,"z":3,"y":320},"end":{"x":13,"z":13,"y":-64},
              "world":"embassies","members":[],"regions":[
                {"title":"Shed","start":{"x":4,"z":4,"y":320},"end":{"x":5,"z":5,"y":-64},
                 "world":"embassies","members":[],"regions":[]}]}]
        """.trimIndent()

        val embassy = embassies(nested).single()

        assertEquals(listOf("Shed"), embassy.subRegions.map { it.title })
        assertEquals(embassy, embassy.subRegions.single().parent)
    }

    @Test
    fun `the real file is one compact line, and reads the same`() {
        val compact = """[{"title":"Jam's Embassy","start":{"x":3,"z":3,"y":320},""" +
            """"end":{"x":13,"z":13,"y":-64},"world":"embassies","members":[],"regions":[]}]"""

        assertEquals(listOf("Jam's Embassy"), embassies(compact).map { it.title })
    }

    @Test
    fun `an embassy with no destination is fine — its owner never set one`() {
        val plain = """
            [{"title":"Empty Embassy","start":{"x":3,"z":3,"y":320},"end":{"x":13,"z":13,"y":-64},
              "world":"embassies","members":[],"regions":[]}]
        """.trimIndent()

        assertTrue(embassies(plain).single().metadata.isEmpty())
    }

    @Test
    fun `a destination the anchor could not read is refused`() {
        val broken = NucleusDeploymentFixture.NUCLEUS_REGIONS.replace("\"yaw\":90.0,", "")

        val error = assertThrows(IllegalArgumentException::class.java) { embassies(broken) }

        assertEquals(
            "embassy \"Jam's Embassy\" has an embassy-destination with no numeric \"yaw\"",
            error.message,
        )
    }

    @Test
    fun `a destination with no world string is refused`() {
        val broken = NucleusDeploymentFixture.NUCLEUS_REGIONS.replace("\"world\":\"world\"", "\"world\":7")

        val error = assertThrows(IllegalArgumentException::class.java) { embassies(broken) }

        assertEquals(
            "embassy \"Jam's Embassy\" has an embassy-destination with no \"world\" string",
            error.message,
        )
    }

    @Test
    fun `a file that is not an array of regions is refused`() {
        val error = assertThrows(IllegalArgumentException::class.java) { embassies("""{"regions":{}}""") }

        assertTrue(error.message!!.startsWith("the Nucleus regions.json is not a JSON array of regions"))
    }

    @Test
    fun `a member that is not a uuid is refused`() {
        val broken = NucleusDeploymentFixture.NUCLEUS_REGIONS
            .replace("\"${NucleusDeploymentFixture.JAM}\"", "\"Jam\"")

        val error = assertThrows(IllegalArgumentException::class.java) { embassies(broken) }

        assertEquals("region \"Jam's Embassy\" has a member that is not a uuid: \"Jam\"", error.message)
    }

    @Test
    fun `a destination pointing at a world this server does not have is named, not refused`() {
        val gone = NucleusDeploymentFixture.NUCLEUS_REGIONS
            .replace("\"world\":\"last_nether\"}", "\"world\":\"creative\"}")

        val found = embassies(gone)

        assertEquals(listOf("Nomad's Embassy → \"creative\""), NucleusRegions.unknownDestinationWorlds(found))
    }

    @Test
    fun `destinations every dimension answers to raise nothing`() {
        assertEquals(emptyList<String>(), NucleusRegions.unknownDestinationWorlds(embassies()))
    }
}
