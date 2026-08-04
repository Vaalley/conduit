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
 * a backend server and one of its dimensions — is [WorldLayout]'s explicit one,
 * so migration is where it gets checked: a region whose world nothing answers to
 * would protect nothing afterwards, and is refused rather than carried over as
 * dead data.
 *
 * **This deliberately still knows Secondary, and must.** `migrate` reads Portal
 * data and writes the two-World save that `mergeWorlds` is later pointed at, so
 * a `last_nether` region is ordinary input here — years before the merge folds
 * it onto Primary and this build stops the server having such a dimension at
 * all. The live [RegionWorlds] no longer resolves those strings, which is why
 * the map below is built from [WorldLayout] rather than inverted out of the
 * region store.
 *
 * The file is re-serialised through the live store, so what the importer
 * writes is exactly what the running server will keep.
 */
object RegionImport {

    /**
     * The dimension regions in the legacy world string [world] live in, or null
     * if nothing the migration knows of answers to it.
     *
     * The Portal's own six strings come first, from [WorldLayout]; anything else
     * is asked of the live region store, which is what keeps the out-of-trio
     * `embassies` (ADR 0003) resolving here as well as on a running server.
     */
    fun dimensionOf(world: String): ResourceKey<Level>? =
        dimensionsByWorldString[world] ?: RegionWorlds.dimensionFor(world)

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
                "which neither of the Portal's Worlds has"
        }
        region.subRegions.forEach(::checkWorld)
    }

    private val dimensionsByWorldString: Map<String, ResourceKey<Level>> =
        WorldLayout.all
            .flatMap { world -> DimensionRole.entries.map { world.legacyWorld(it) to world.dimension(it) } }
            .toMap()
}
