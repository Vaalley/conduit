package eu.mctraveler.region

import net.minecraft.SharedConstants
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The dimension ↔ legacy world-string mapping regions are stored under.
 * Primary is the vanilla trio (`world*`); Secondary is ticket 04's
 * datapack-defined trio (`last*`). The legacy strings are the Portal's —
 * migrated `regions.json` data depends on them.
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
    fun `the Secondary trio maps to the Portal's last strings`() {
        assertEquals("last", RegionWorlds.legacyName(secondary("secondary")))
        assertEquals("last_nether", RegionWorlds.legacyName(secondary("secondary_nether")))
        assertEquals("last_the_end", RegionWorlds.legacyName(secondary("secondary_end")))
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
    fun `the World analog is read from the legacy string prefix`() {
        assertFalse(RegionWorlds.isSecondaryWorld("world"))
        assertFalse(RegionWorlds.isSecondaryWorld("world_the_end"))
        assertTrue(RegionWorlds.isSecondaryWorld("last"))
        assertTrue(RegionWorlds.isSecondaryWorld("last_nether"))
    }

    @Test
    fun `locate's server-dimension info follows the Portal's mapping`() {
        assertEquals("primary/overworld", RegionWorlds.locateInfo("world"))
        assertEquals("primary/nether", RegionWorlds.locateInfo("world_nether"))
        assertEquals("primary/end", RegionWorlds.locateInfo("world_the_end"))
        assertEquals("secondary/overworld", RegionWorlds.locateInfo("last"))
        assertEquals("secondary/nether", RegionWorlds.locateInfo("last_nether"))
        assertEquals("secondary/end", RegionWorlds.locateInfo("last_the_end"))
    }

    @Test
    fun `locate names the embassies dimension, which belongs to no World`() {
        assertEquals("embassies", RegionWorlds.locateInfo("embassies"))
    }
}
