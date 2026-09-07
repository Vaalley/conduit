package eu.mctraveler.importer

import eu.mctraveler.MCTraveler
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.worlds.DimensionRole
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/** One World as the merged save keys it: a trio of dimensions under a World id. */
class WorldTrio internal constructor(
    /** The Portal's `lastServer` value, and the mod's World id. */
    val id: String,
    private val trio: Map<DimensionRole, ResourceKey<Level>>,
    private val legacyWorlds: Map<DimensionRole, String>,
) {
    fun dimension(role: DimensionRole): ResourceKey<Level> = trio.getValue(role)

    /** The dimension id string [role] takes in this World, as playerdata records it. */
    fun dimensionId(role: DimensionRole): String = dimension(role).identifier().toString()

    /** The legacy world string `regions.json` records [role]'s dimension under. */
    fun legacyWorld(role: DimensionRole): String = legacyWorlds.getValue(role)

    override fun toString(): String = "WorldTrio($id)"
}

/**
 * The World topology, as the importer must write it.
 *
 * This restates what the server once knew, because the importer runs without a
 * server (and so without the dimension registry) — and, since the Worlds
 * subsystem was retired, because there is no longer anything live to ask. The
 * tools still meet Secondary constantly: `mergeWorlds` runs offline against a
 * save whose Secondary dimension folders and Secondary-keyed Regions are exactly
 * what it is there to move, and it runs *before* the build that stops the server
 * creating those dimensions is ever deployed. So Secondary's ids and its legacy
 * world strings live on here, where they are historical facts about a save on
 * disk rather than claims about a running server.
 *
 * Primary's half is still derived from [RegionWorlds] rather than spelled out,
 * because Primary's dimensions are the ones the live server does still have and
 * the two statements must not drift. Secondary's is spelled out, because the
 * live Region layer stopped knowing those strings when it stopped having a
 * Secondary to name; the unit tier pins them.
 *
 * Both backends were plain vanilla servers, so everything *read* during a
 * migration is keyed by the vanilla trio ([backendRole]); which World that
 * backend becomes is decided by which directory it was read from.
 */
object WorldLayout {
    val PRIMARY = WorldTrio(
        "primary",
        DimensionRole.entries.associateWith { it.vanilla },
        DimensionRole.entries.associateWith { RegionWorlds.legacyName(it.vanilla) },
    )

    val SECONDARY = WorldTrio(
        "secondary",
        mapOf(
            DimensionRole.OVERWORLD to secondaryDimension("secondary"),
            DimensionRole.NETHER to secondaryDimension("secondary_nether"),
            DimensionRole.END to secondaryDimension("secondary_end"),
        ),
        // The Portal's own strings for its second backend, kept verbatim: every
        // Region the merge has to move is recorded under one of them.
        mapOf(
            DimensionRole.OVERWORLD to "last",
            DimensionRole.NETHER to "last_nether",
            DimensionRole.END to "last_the_end",
        ),
    )

    val all: List<WorldTrio> = listOf(PRIMARY, SECONDARY)

    fun byId(id: String): WorldTrio? = all.firstOrNull { it.id == id }

    /** The trio role a backend's dimension id names, or null if no World has one. */
    fun backendRole(dimensionId: String): DimensionRole? =
        DimensionRole.entries.firstOrNull { it.vanilla.identifier().toString() == dimensionId }

    private fun secondaryDimension(path: String): ResourceKey<Level> =
        ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(MCTraveler.MOD_ID, path))
}
