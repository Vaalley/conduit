package eu.mctraveler.importer

import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.worlds.DimensionRole
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The importer's copy of the World topology — which, since the Worlds were
 * retired, is the only copy left.
 *
 * Primary's half is still cross-checked against the shipped mod's own statement
 * of it, because the live server still has those dimensions and the two must not
 * drift. Secondary's half can no longer be cross-checked against anything, which
 * is exactly why it is pinned here as literals: `mergeWorlds` runs offline
 * against a save that still has Secondary's dimension folders and Secondary's
 * `last*` Regions in it, and if these values are wrong it will not find them.
 */
class WorldLayoutTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun `Primary is the vanilla trio`() {
        assertEquals(Level.OVERWORLD, WorldLayout.PRIMARY.dimension(DimensionRole.OVERWORLD))
        assertEquals(Level.NETHER, WorldLayout.PRIMARY.dimension(DimensionRole.NETHER))
        assertEquals(Level.END, WorldLayout.PRIMARY.dimension(DimensionRole.END))
    }

    @Test
    fun `Primary's legacy world strings are the live region store's own`() {
        assertEquals(RegionWorlds.legacyName(Level.OVERWORLD), WorldLayout.PRIMARY.legacyWorld(DimensionRole.OVERWORLD))
        assertEquals(RegionWorlds.legacyName(Level.NETHER), WorldLayout.PRIMARY.legacyWorld(DimensionRole.NETHER))
        assertEquals(RegionWorlds.legacyName(Level.END), WorldLayout.PRIMARY.legacyWorld(DimensionRole.END))
        // ...and those are still the Portal's, which is what a relocated Region
        // comes to say and what the live server reads back.
        assertEquals("world", WorldLayout.PRIMARY.legacyWorld(DimensionRole.OVERWORLD))
        assertEquals("world_nether", WorldLayout.PRIMARY.legacyWorld(DimensionRole.NETHER))
        assertEquals("world_the_end", WorldLayout.PRIMARY.legacyWorld(DimensionRole.END))
    }

    @Test
    fun `Secondary's dimensions are the ones its chunk data is still filed under`() {
        // The merge tool finds Secondary's region files by these ids, and this
        // build no longer ships anything that could confirm them.
        assertEquals("mctraveler:secondary", WorldLayout.SECONDARY.dimensionId(DimensionRole.OVERWORLD))
        assertEquals("mctraveler:secondary_nether", WorldLayout.SECONDARY.dimensionId(DimensionRole.NETHER))
        assertEquals("mctraveler:secondary_end", WorldLayout.SECONDARY.dimensionId(DimensionRole.END))
    }

    @Test
    fun `Secondary's trio is the one regions are already stored against`() {
        assertEquals("last", WorldLayout.SECONDARY.legacyWorld(DimensionRole.OVERWORLD))
        assertEquals("last_nether", WorldLayout.SECONDARY.legacyWorld(DimensionRole.NETHER))
        assertEquals("last_the_end", WorldLayout.SECONDARY.legacyWorld(DimensionRole.END))
        // And the live Region layer deliberately no longer answers to them, so
        // these two statements cannot be collapsed into one again.
        assertNull(RegionWorlds.dimensionFor("last"))
    }

    @Test
    fun `the Worlds are keyed by the Portal's lastServer values`() {
        assertEquals("primary", WorldLayout.PRIMARY.id)
        assertEquals("secondary", WorldLayout.SECONDARY.id)
        assertEquals(WorldLayout.SECONDARY, WorldLayout.byId("secondary"))
        assertNull(WorldLayout.byId("tertiary"))
    }

    @Test
    fun `backend dimension ids read back as trio roles`() {
        assertEquals(DimensionRole.OVERWORLD, WorldLayout.backendRole("minecraft:overworld"))
        assertEquals(DimensionRole.NETHER, WorldLayout.backendRole("minecraft:the_nether"))
        assertEquals(DimensionRole.END, WorldLayout.backendRole("minecraft:the_end"))
        assertNull(WorldLayout.backendRole("mctraveler:secondary"))
    }
}
