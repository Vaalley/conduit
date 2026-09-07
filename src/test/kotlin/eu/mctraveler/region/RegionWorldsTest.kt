package eu.mctraveler.region

import net.minecraft.SharedConstants
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The dimension ↔ legacy world-string mapping regions are stored under. The
 * vanilla trio is the Portal's `world*`, and migrated `regions.json` data
 * depends on those strings staying exactly as they are.
 *
 * The Portal's other backend was `last*`, and this suite used to assert that
 * mapping too. The merge moved every Region that named one onto Primary and the
 * dimensions themselves are gone, so those strings now name nowhere on this
 * server — which is what is asserted instead, below. They survive as the merge
 * tool's own statement of a save it reads offline, pinned in `WorldLayoutTest`.
 */
class RegionWorldsTest {
    companion object {
        // The vanilla Level constants used here pull in registry statics that
        // need the game bootstrapped, even in the unit tier.
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private fun secondary(path: String): ResourceKey<Level> =
        ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("mctraveler", path))

    @Test
    fun `the Primary trio maps to the Portal's world strings`() {
        assertEquals("world", RegionWorlds.legacyName(Level.OVERWORLD))
        assertEquals("world_nether", RegionWorlds.legacyName(Level.NETHER))
        assertEquals("world_the_end", RegionWorlds.legacyName(Level.END))
    }

    @Test
    fun `the retired Secondary trio no longer maps to anything`() {
        // Its dimensions do not exist on this server, so naming one of them must
        // not produce a legacy world string that a live Region could be filed
        // under — an unswept Region or destination has to be visibly nowhere
        // rather than quietly somewhere.
        assertEquals("mctraveler:secondary", RegionWorlds.legacyName(secondary("secondary")))
        assertEquals("mctraveler:secondary_nether", RegionWorlds.legacyName(secondary("secondary_nether")))
        assertEquals("mctraveler:secondary_end", RegionWorlds.legacyName(secondary("secondary_end")))
    }

    @Test
    fun `the Portal's last strings name no dimension this server has`() {
        // The mirror of the above, and the one that matters for a saved embassy
        // destination the merge did not reach: it must lead nowhere.
        assertNull(RegionWorlds.dimensionFor("last"))
        assertNull(RegionWorlds.dimensionFor("last_nether"))
        assertNull(RegionWorlds.dimensionFor("last_the_end"))
    }

    @Test
    fun `the Primary trio's strings still resolve, because those are the map`() {
        assertEquals(Level.OVERWORLD, RegionWorlds.dimensionFor("world"))
        assertEquals(Level.NETHER, RegionWorlds.dimensionFor("world_nether"))
        assertEquals(Level.END, RegionWorlds.dimensionFor("world_the_end"))
    }

    @Test
    fun `the embassies dimension keeps Nucleus's own world name`() {
        assertEquals("embassies", RegionWorlds.legacyName(secondary("embassies")))
    }

    @Test
    fun `an unknown dimension maps to its own id and never a legacy name`() {
        val key = secondary("mystery")
        assertEquals("mctraveler:mystery", RegionWorlds.legacyName(key))
    }

    @Test
    fun `locate names the dimension, and no longer a server`() {
        // The Portal printed `primary/overworld` because a Region genuinely
        // lived on one of two backends. There is one map now, so the server half
        // named something that does not exist (merge spec, User Story 25).
        assertEquals("overworld", RegionWorlds.locateInfo("world"))
        assertEquals("nether", RegionWorlds.locateInfo("world_nether"))
        assertEquals("end", RegionWorlds.locateInfo("world_the_end"))
    }

    @Test
    fun `locate names the embassies dimension, which belongs to no World`() {
        assertEquals("embassies", RegionWorlds.locateInfo("embassies"))
    }
}
