package eu.mctraveler.region

import eu.mctraveler.MCTraveler
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * The one place dimensions and the Portal's legacy world strings meet.
 *
 * Regions are stored under the Portal's strings — `world`, `world_nether`,
 * `world_the_end` — so migrated `regions.json` data reads unchanged. Keeping
 * that vocabulary long after the Portal is what lets a Region written in 2019
 * still protect the same ground without anybody rewriting a file.
 *
 * The Portal's *other* backend was recorded as `last`, `last_nether` and
 * `last_the_end`, and those strings are deliberately absent. The merge moved
 * every Region that named one of them onto Primary and rewrote it (see
 * [eu.mctraveler.importer.MergeRegions]), and the dimensions they named no
 * longer exist on this server, so a `last*` string arriving here now names
 * nowhere — which is exactly what [dimensionFor] should say about it. The
 * strings themselves survive where they are still true: in
 * [eu.mctraveler.importer.WorldLayout], as facts about a save the merge tool
 * reads offline.
 *
 * The out-of-trio embassies dimension (ADR 0003) is here because Nucleus stored
 * its embassy regions under its Bukkit world name, `embassies`, so keeping that
 * string is what lets the twenty imported embassies be found where they stand.
 */
object RegionWorlds {
    private fun modDimension(path: String): ResourceKey<Level> =
        ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(MCTraveler.MOD_ID, path))

    /** The legacy world string of the embassies dimension — Nucleus's world name. */
    const val EMBASSIES = "embassies"

    private val legacyNames: Map<ResourceKey<Level>, String> = mapOf(
        Level.OVERWORLD to "world",
        Level.NETHER to "world_nether",
        Level.END to "world_the_end",
        modDimension("embassies") to EMBASSIES,
    )

    /**
     * The legacy world string regions in [dimension] live under. A dimension
     * with no legacy name keeps its own id string — distinct from every legacy
     * name, so its regions can never bleed into the map players are on.
     */
    fun legacyName(dimension: ResourceKey<Level>): String =
        legacyNames[dimension] ?: dimension.identifier().toString()

    private val dimensions: Map<String, ResourceKey<Level>> =
        legacyNames.entries.associate { (dimension, world) -> world to dimension }

    /**
     * The dimension a legacy world string names, or null when nothing on this
     * server answers to it — the inverse of [legacyName], for the stored
     * destinations that only ever say "world" or "world_nether".
     *
     * Null is a real answer, not an error: a saved embassy destination naming a
     * world this server no longer has is simply not somewhere to go. Since the
     * merge that is also the answer for `last*`, and it is the right one — an
     * unswept destination pointing into the retired Secondary trio must lead
     * nowhere rather than somewhere plausible.
     */
    fun dimensionFor(world: String): ResourceKey<Level>? = dimensions[world]

    /**
     * `/rg locate`'s rendering of a legacy world string: the dimension, by the
     * Portal's own mapping.
     *
     * The Portal printed `server/dimension`, because a Region genuinely lived on
     * one of two backend servers. There is one map now, so naming a server would
     * be naming something that does not exist — the half that carried real
     * information is the dimension, and that is all that is left (merge spec,
     * User Story 25). The embassies dimension has no role in any trio to name,
     * so it is simply itself.
     */
    fun locateInfo(world: String): String {
        if (world == EMBASSIES) return EMBASSIES
        return when {
            world.contains("nether") -> "nether"
            world.contains("end") -> "end"
            else -> "overworld"
        }
    }
}
