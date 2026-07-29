package eu.mctraveler.importer

import eu.mctraveler.MCTraveler
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
) {
    fun dimension(role: DimensionRole): ResourceKey<Level> = trio.getValue(role)

    /** The dimension id string [role] takes in this World, as playerdata records it. */
    fun dimensionId(role: DimensionRole): String = dimension(role).identifier().toString()

    override fun toString(): String = "WorldTrio($id)"
}

/**
 * The World topology, as the importer must write it.
 *
 * This restates what the live Worlds service knows, because the importer runs
 * without a server (and so without the dimension registry). The unit tier pins
 * it to the shipped mod's own statements of the same topology — the trio roles
 * and the region store's dimension map.
 *
 * Both backends were plain vanilla servers, so everything *read* during a
 * migration is keyed by the vanilla trio ([backendRole]); which World that
 * backend becomes is decided by which directory it was read from.
 */
object WorldLayout {
    val PRIMARY = WorldTrio("primary", DimensionRole.entries.associateWith { it.vanilla })

    val SECONDARY = WorldTrio(
        "secondary",
        mapOf(
            DimensionRole.OVERWORLD to secondaryDimension("secondary"),
            DimensionRole.NETHER to secondaryDimension("secondary_nether"),
            DimensionRole.END to secondaryDimension("secondary_end"),
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
