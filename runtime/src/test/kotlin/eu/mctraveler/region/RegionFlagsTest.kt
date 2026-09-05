package eu.mctraveler.region

import eu.mctraveler.MinecraftTestBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * [RegionFlags.seedDefaults] and [RegionFlags.migrateLegacy] — the flag
 * catalog's two entry points, exercised over the legacy-migration table this
 * rework's plan spells out row by row.
 */
class RegionFlagsTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() = MinecraftTestBootstrap.ensure()
    }

    private fun region(vararg flags: String): Region {
        val region = Region(title = "Test", world = "world", startX = 0, startZ = 0, endX = 1, endZ = 1)
        region.flags.addAll(flags)
        return region
    }

    // ---- seedDefaults ----

    @Test
    fun `seedDefaults adds every default-allowed flag and nothing else`() {
        val region = region()
        RegionFlags.seedDefaults(region)

        val expectedPresent = RegionFlags.ALL.filter { it.defaultAllowed }.map { it.id }.toSet()
        val expectedAbsent = RegionFlags.ALL.filter { !it.defaultAllowed }.map { it.id }.toSet()

        assertEquals(expectedPresent, region.flags)
        for (id in expectedAbsent) assertFalse(id in region.flags, "$id should not be seeded")
    }

    @Test
    fun `seeding is idempotent over an already-seeded region`() {
        val region = region()
        RegionFlags.seedDefaults(region)
        val once = region.flags.toSet()
        RegionFlags.seedDefaults(region)
        assertEquals(once, region.flags)
    }

    // ---- migrateLegacy: straight renames, same polarity ----

    @Test
    fun `ENABLE_EXPLOSIONS present renames straight to EXPLOSIONS`() {
        val region = region("ENABLE_EXPLOSIONS")
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("EXPLOSIONS" in region.flags)
        assertFalse("ENABLE_EXPLOSIONS" in region.flags)
    }

    @Test
    fun `ENABLE_EXPLOSIONS absent leaves EXPLOSIONS absent`() {
        val region = region()
        RegionFlags.migrateLegacy(region.flags)
        assertFalse("EXPLOSIONS" in region.flags)
    }

    @Test
    fun `ENABLE_PUBLIC_CONTAINERS renames straight to PUBLIC_CHESTS`() {
        val region = region("ENABLE_PUBLIC_CONTAINERS")
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("PUBLIC_CHESTS" in region.flags)
        assertFalse("PUBLIC_CONTAINERS" in region.flags)
    }

    @Test
    fun `the not-yet-released PUBLIC_CONTAINERS id is remapped defensively to PUBLIC_CHESTS`() {
        val region = region("PUBLIC_CONTAINERS")
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("PUBLIC_CHESTS" in region.flags)
        assertFalse("PUBLIC_CONTAINERS" in region.flags)
    }

    @Test
    fun `ENABLE_FIRE_DAMAGE renames straight to FIRE_DAMAGE`() {
        val region = region("ENABLE_FIRE_DAMAGE")
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("FIRE_DAMAGE" in region.flags)
    }

    @Test
    fun `ENABLE_PUBLIC_VILLAGER_TRADING renames straight to PUBLIC_VILLAGERS`() {
        val region = region("ENABLE_PUBLIC_VILLAGER_TRADING")
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("PUBLIC_VILLAGERS" in region.flags)
    }

    // ---- migrateLegacy: NO_SCOREBOARD <-> SCOREBOARD (inverted) ----

    @Test
    fun `NO_SCOREBOARD present removes SCOREBOARD`() {
        val region = region("NO_SCOREBOARD")
        RegionFlags.migrateLegacy(region.flags)
        assertFalse("SCOREBOARD" in region.flags)
        assertFalse("NO_SCOREBOARD" in region.flags)
    }

    @Test
    fun `NO_SCOREBOARD absent adds SCOREBOARD`() {
        val region = region()
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("SCOREBOARD" in region.flags)
    }

    // ---- migrateLegacy: DISABLE_GATES splits three ways ----

    @Test
    fun `DISABLE_GATES present removes all three of GATES DOORS TRAPDOORS`() {
        val region = region("DISABLE_GATES")
        RegionFlags.migrateLegacy(region.flags)
        assertFalse("GATES" in region.flags)
        assertFalse("DOORS" in region.flags)
        assertFalse("TRAPDOORS" in region.flags)
    }

    @Test
    fun `DISABLE_GATES absent leaves all three of GATES DOORS TRAPDOORS present`() {
        val region = region()
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("GATES" in region.flags)
        assertTrue("DOORS" in region.flags)
        assertTrue("TRAPDOORS" in region.flags)
    }

    // ---- migrateLegacy: DISABLE_PLAYER_FALL_DAMAGE -> FALL_DAMAGE (flip) ----
    //
    // FALL_DAMAGE now means "a fall hurts here", on by default. The old
    // DISABLE_PLAYER_FALL_DAMAGE was the opposite marker.

    @Test
    fun `DISABLE_PLAYER_FALL_DAMAGE present keeps the region fall-safe`() {
        val region = region("DISABLE_PLAYER_FALL_DAMAGE")
        RegionFlags.migrateLegacy(region.flags)
        assertFalse("FALL_DAMAGE" in region.flags)
        assertFalse("DISABLE_PLAYER_FALL_DAMAGE" in region.flags)
    }

    @Test
    fun `DISABLE_PLAYER_FALL_DAMAGE absent ends up with FALL_DAMAGE on`() {
        // Old absence meant "fall damage applies normally", which is exactly
        // the new default-true FALL_DAMAGE baseline.
        val region = region()
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("FALL_DAMAGE" in region.flags)
    }

    // ---- migrateLegacy: DISABLE_PUBLIC_REDSTONE_TRIGGERS -> PUBLIC_REDSTONE ----

    @Test
    fun `DISABLE_PUBLIC_REDSTONE_TRIGGERS present removes PUBLIC_REDSTONE`() {
        val region = region("DISABLE_PUBLIC_REDSTONE_TRIGGERS")
        RegionFlags.migrateLegacy(region.flags)
        assertFalse("PUBLIC_REDSTONE" in region.flags)
    }

    @Test
    fun `DISABLE_PUBLIC_REDSTONE_TRIGGERS absent leaves PUBLIC_REDSTONE present`() {
        val region = region()
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("PUBLIC_REDSTONE" in region.flags)
    }

    // ---- migrateLegacy: DISABLE_WEIGHTED_PRESSURE_PLATES -> WEIGHTED_PRESSURE_PLATES ----

    @Test
    fun `DISABLE_WEIGHTED_PRESSURE_PLATES present removes WEIGHTED_PRESSURE_PLATES`() {
        val region = region("DISABLE_WEIGHTED_PRESSURE_PLATES")
        RegionFlags.migrateLegacy(region.flags)
        assertFalse("WEIGHTED_PRESSURE_PLATES" in region.flags)
    }

    @Test
    fun `DISABLE_WEIGHTED_PRESSURE_PLATES absent leaves WEIGHTED_PRESSURE_PLATES present`() {
        val region = region()
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("WEIGHTED_PRESSURE_PLATES" in region.flags)
    }

    // ---- migrateLegacy: DISABLE_ANIMAL_PROTECTION -> ANIMAL_PROTECTION (flip) ----

    @Test
    fun `DISABLE_ANIMAL_PROTECTION present removes ANIMAL_PROTECTION`() {
        val region = region("DISABLE_ANIMAL_PROTECTION")
        RegionFlags.migrateLegacy(region.flags)
        assertFalse("ANIMAL_PROTECTION" in region.flags)
    }

    @Test
    fun `DISABLE_ANIMAL_PROTECTION absent adds ANIMAL_PROTECTION`() {
        val region = region()
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("ANIMAL_PROTECTION" in region.flags)
    }

    // ---- migrateLegacy: DISABLE_PVP -> PVP (flip) ----

    @Test
    fun `DISABLE_PVP present leaves PVP absent`() {
        val region = region("DISABLE_PVP")
        RegionFlags.migrateLegacy(region.flags)
        assertFalse("PVP" in region.flags)
    }

    @Test
    fun `DISABLE_PVP absent adds PVP`() {
        val region = region()
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("PVP" in region.flags)
    }

    // ---- migrateLegacy: unchanged strings ----

    @Test
    fun `PUBLIC ADMIN and EMBASSY pass through unchanged`() {
        val region = region("PUBLIC", "ADMIN", "EMBASSY")
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("PUBLIC" in region.flags)
        assertTrue("ADMIN" in region.flags)
        assertTrue("EMBASSY" in region.flags)
    }

    // ---- migrateLegacy: brand-new flags get the seeded baseline ----

    @Test
    fun `an untouched legacy region ends up with every brand-new default-allowed flag`() {
        val region = region()
        RegionFlags.migrateLegacy(region.flags)
        assertTrue("BOATS" in region.flags)
        assertTrue("MINECARTS" in region.flags)
        assertTrue("RIDEABLE" in region.flags)
        assertFalse("WIND_CHARGES" in region.flags)
        assertFalse("POTIONS" in region.flags)
    }

    // ---- migrateLegacy: a fully-migrated region matches a freshly-seeded one ----

    @Test
    fun `an old region touched by nothing ends up identical to a freshly created one`() {
        val legacy = region()
        RegionFlags.migrateLegacy(legacy.flags)

        val fresh = region()
        RegionFlags.seedDefaults(fresh)

        assertEquals(fresh.flags, legacy.flags)
    }
}
