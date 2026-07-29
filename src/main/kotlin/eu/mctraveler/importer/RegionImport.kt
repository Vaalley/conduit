package eu.mctraveler.importer

import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionStore
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.worlds.DimensionRole
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * Region migration (spec User Story 43, "regions with world-name mapping").
 *
 * The port kept the Portal's `regions.json` format *and* its world-name
 * strings, so a migrated file is the legacy file: `world`/`world_nether`/
 * `world_the_end` are Primary's trio and `last`/`last_nether`/`last_the_end`
 * are Secondary's. The mapping that used to be implicit — a world string meant
 * a backend server and one of its dimensions — is now [RegionWorlds]' explicit
 * dimension map, so migration is where it gets checked: a region whose world
 * no dimension answers to would protect nothing on the merged server, and is
 * refused rather than carried over as dead data.
 *
 * The file is re-serialised through the live store, so what the importer
 * writes is exactly what the running server will keep.
 */
object RegionImport {

    /**
     * The dimension regions in the legacy world string [world] live in, or
     * null if no World of the merged server has one. Inverted from
     * [RegionWorlds] itself, so the two can never drift apart.
     */
    fun dimensionOf(world: String): ResourceKey<Level>? = dimensionsByWorldString[world]

    /**
     * [text] — a Portal `regions.json` — as the merged server's region store,
     * with every region (and sub-region) checked against the new dimensions.
     */
    fun migrate(text: String): String {
        val regions = RegionStore.parse(text)
        regions.forEach(::checkWorld)
        return RegionStore.serialize(regions)
    }

    private fun checkWorld(region: Region) {
        require(dimensionOf(region.world) != null) {
            "region \"${region.title}\" is in world \"${region.world}\", " +
                "which no World of the merged server has"
        }
        region.subRegions.forEach(::checkWorld)
    }

    private val dimensionsByWorldString: Map<String, ResourceKey<Level>> =
        WorldLayout.all
            .flatMap { world -> DimensionRole.entries.map(world::dimension) }
            .associateBy(RegionWorlds::legacyName)
}
