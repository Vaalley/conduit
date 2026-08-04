package eu.mctraveler.worlds

import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * Which of the three kinds of place a dimension is: an overworld, a nether or an
 * end.
 *
 * This began as the place a dimension took in a *World's* trio, back when the
 * server ran two of them and per-World player state had to name dimensions in a
 * way that meant the same thing in either. The Worlds are gone and that
 * resolution went with them, but the distinction itself outlived its reason: the
 * migration tools still read two Portal-era backends and a merge still has to
 * say that Secondary's nether becomes Primary's nether, and none of that can ask
 * a running server which dimension is which.
 *
 * So this is now the importer's vocabulary rather than the server's. [id] is the
 * form the Portal-era player records persisted (a Per-World Bucket's `dimension`
 * field, which the merge tool still reads). [vanilla] is the dimension Minecraft
 * itself gives the role — the one trio that is left, and the one every backend
 * save was written against.
 */
enum class DimensionRole(val id: String, val vanilla: ResourceKey<Level>) {
    OVERWORLD("overworld", Level.OVERWORLD),
    NETHER("nether", Level.NETHER),
    END("end", Level.END);

    companion object {
        fun fromId(id: String): DimensionRole? = entries.firstOrNull { it.id == id }
    }
}
