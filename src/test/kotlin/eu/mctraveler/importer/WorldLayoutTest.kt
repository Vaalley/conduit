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
 * The importer's copy of the World topology. It cannot ask the live Worlds
 * service (that needs a running server), so these tests pin it to the two
 * places the shipped mod states the same thing: the trio roles and the region
 * store's dimension ↔ legacy-world map.
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
    fun `Secondary's trio is the one regions are already stored against`() {
        assertEquals("last", RegionWorlds.legacyName(WorldLayout.SECONDARY.dimension(DimensionRole.OVERWORLD)))
        assertEquals("last_nether", RegionWorlds.legacyName(WorldLayout.SECONDARY.dimension(DimensionRole.NETHER)))
        assertEquals("last_the_end", RegionWorlds.legacyName(WorldLayout.SECONDARY.dimension(DimensionRole.END)))
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
