package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Region migration: the Portal's world-name strings have to name dimensions
 * the merged server actually has, or the regions they protect would silently
 * protect nothing.
 */
class RegionImportTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private val legacyFile = """
        {
          "regions": {
            "0": {
              "title": "Spawn Town",
              "start-x": -10,
              "start-z": -10,
              "end-x": 10,
              "end-z": 10,
              "world": "world",
              "members": [
                "069a79f4-44e9-4726-a5be-fca90e38aaf5"
              ],
              "sub-regions": {
                "0": {
                  "title": "Vault",
                  "start-x": -2,
                  "start-z": -2,
                  "end-x": 2,
                  "end-z": 2,
                  "world": "world",
                  "members": []
                }
              }
            },
            "1": {
              "title": "Far Outpost",
              "start-x": 500,
              "start-z": 500,
              "end-x": 520,
              "end-z": 520,
              "world": "last_nether",
              "members": []
            }
          }
        }
    """.trimIndent()

    @Test
    fun `every world string the Portal wrote names a dimension the migration writes`() {
        assertEquals(WorldLayout.PRIMARY.dimension(DimensionRole.OVERWORLD), RegionImport.dimensionOf("world"))
        assertEquals(WorldLayout.PRIMARY.dimension(DimensionRole.NETHER), RegionImport.dimensionOf("world_nether"))
        assertEquals(WorldLayout.PRIMARY.dimension(DimensionRole.END), RegionImport.dimensionOf("world_the_end"))
        assertEquals(WorldLayout.SECONDARY.dimension(DimensionRole.OVERWORLD), RegionImport.dimensionOf("last"))
        assertEquals(WorldLayout.SECONDARY.dimension(DimensionRole.NETHER), RegionImport.dimensionOf("last_nether"))
        assertEquals(WorldLayout.SECONDARY.dimension(DimensionRole.END), RegionImport.dimensionOf("last_the_end"))
    }

    @Test
    fun `a world string no dimension answers to is nobody's`() {
        assertNull(RegionImport.dimensionOf("creative"))
    }

    @Test
    fun `a migrated regions file is exactly what the live region store keeps`() {
        assertEquals(legacyFile, RegionImport.migrate(legacyFile))
    }

    @Test
    fun `a region in a world the migration does not have is refused`() {
        val orphaned = legacyFile.replace("\"world\": \"last_nether\"", "\"world\": \"creative\"")

        val error = assertThrows(IllegalArgumentException::class.java) { RegionImport.migrate(orphaned) }

        assertEquals(
            "region \"Far Outpost\" is in world \"creative\", which neither of the Portal's Worlds has",
            error.message,
        )
    }

    @Test
    fun `a sub-region in a world the migration does not have is refused too`() {
        val orphaned = """
            {"regions":{"0":{"title":"Spawn Town","start-x":-10,"start-z":-10,"end-x":10,"end-z":10,
            "world":"world","members":[],"sub-regions":{"0":{"title":"Vault","start-x":-2,"start-z":-2,
            "end-x":2,"end-z":2,"world":"creative","members":[]}}}}}
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) { RegionImport.migrate(orphaned) }

        assertEquals(
            "region \"Vault\" is in world \"creative\", which neither of the Portal's Worlds has",
            error.message,
        )
    }
}
