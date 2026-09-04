package eu.mctraveler.region

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The Region service: the live region tree, its legacy-format persistence, and
 * the geometry queries everything region-shaped is built on — commands here,
 * membership/scoreboard (ticket 13) and protection (tickets 14–15) on top.
 *
 * Regions load once at construction (server start); every mutation is a
 * synchronous whole-file rewrite of `regions.json` (as the Portal's were).
 * Mutations to a region's own fields (title, flags, y bounds, members) are
 * made on the [Region] and followed by [save]; structural changes go through
 * [add] and [remove], which keep parent links wired and save themselves.
 * All access is expected from the server thread.
 */
class RegionService(private val file: Path) {

    /** The root regions, in file order. Sub-regions hang off their parents. */
    val roots: MutableList<Region> =
        if (Files.exists(file)) RegionStore.parse(Files.readString(file)) else mutableListOf()

    /** What [onChange] registered, in registration order. */
    private val listeners = mutableListOf<() -> Unit>()

    /**
     * Runs [listener] after every save — which is after every change, since a
     * mutation that is not followed by a [save] is a mutation that would not
     * survive a restart either.
     *
     * For the readers that keep their own picture of the tree and cannot poll
     * for one: the Lodeway map layer
     * ([eu.mctraveler.lodeway.LodewayRegions]) is the first. A listener runs on
     * the server thread inside the command that changed something, so it must
     * be cheap, and it must not throw: an exception here would surface as a
     * failed `/rg` command.
     */
    fun onChange(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun save() {
        file.parent?.let(Files::createDirectories)
        Files.writeString(file, RegionStore.serialize(roots))
        listeners.forEach { it() }
    }

    /** Attaches [region] under [parent] (or as a root) and saves. */
    fun add(region: Region, parent: Region?) {
        region.parent = parent
        (parent?.subRegions ?: roots).add(region)
        save()
    }

    /** Detaches [region] from its parent (or the root list) and saves. */
    fun remove(region: Region) {
        (region.parent?.subRegions ?: roots).remove(region)
        region.parent = null
        save()
    }

    /** Stable tree-path id used by the Lodeway markers and passport records. */
    fun idOf(region: Region): String? {
        fun find(regions: List<Region>, prefix: String): String? {
            regions.forEachIndexed { index, candidate ->
                val id = if (prefix.isEmpty()) "$index" else "$prefix.$index"
                if (candidate === region) return id
                find(candidate.subRegions, id)?.let { return it }
            }
            return null
        }
        return find(roots, "")
    }

    /** Resolves a stable tree-path id, or null for malformed/orphaned ids. */
    fun byId(id: String): Region? {
        val indexes = id.split('.').mapNotNull { it.toIntOrNull() }
        if (indexes.size != id.count { it == '.' } + 1) return null
        var current = roots.getOrNull(indexes.firstOrNull() ?: return null) ?: return null
        for (index in indexes.drop(1)) current = current.subRegions.getOrNull(index) ?: return null
        return current
    }

    /**
     * The deepest region containing the block position in the given World
     * (legacy world string), or null — sub-regions win over their parents.
     *
     * Environmental protection (ticket 15) asks this from block ticks and
     * explosion maths, so it walks the roots rather than filtering them into a
     * new list: a server with no regions pays one empty-list check, and one
     * with regions pays a scan and no allocation.
     */
    fun regionAt(world: String, x: Int, y: Int, z: Int): Region? {
        var found: Region = roots.firstOrNull { it.world == world && it.contains(x, y, z) } ?: return null
        while (true) {
            found = found.subRegions.firstOrNull { it.contains(x, y, z) } ?: return found
        }
    }

    /**
     * The first region whose x/z footprint intersects the given column in the
     * given World, scanning the whole tree in file order — full-intersection
     * overlap detection (deviation 3). [excluding] names the prospective
     * parent of a region being created: it and its ancestors are not overlaps
     * (a sub-region always lies inside them), but their other descendants are.
     */
    fun firstIntersecting(
        world: String,
        minX: Int,
        maxX: Int,
        minZ: Int,
        maxZ: Int,
        excluding: Region? = null,
    ): Region? {
        val ancestors = excluding?.selfAndAncestors()?.toSet() ?: emptySet()

        fun scan(regions: List<Region>): Region? {
            for (region in regions) {
                if (region.world == world && region !in ancestors &&
                    region.intersectsColumn(minX, maxX, minZ, maxZ)
                ) {
                    return region
                }
                scan(region.subRegions)?.let { return it }
            }
            return null
        }
        return scan(roots)
    }

    /**
     * The first region that would overlap [region] if its x/z footprint grew
     * to [minX]..[maxX] × [minZ]..[maxZ] — the `/rg extend` overlap check.
     *
     * Scans the whole tree in file order like [firstIntersecting], but skips
     * [region] itself (and, by not recursing past it, its sub-regions, which
     * lie inside it) and its ancestors (which contain it). A sibling or cousin
     * the grown footprint now reaches is a real overlap and is returned.
     */
    fun firstOverlappingExtension(
        region: Region,
        minX: Int,
        maxX: Int,
        minZ: Int,
        maxZ: Int,
    ): Region? {
        val ancestors = region.selfAndAncestors().toSet()

        fun scan(regions: List<Region>): Region? {
            for (candidate in regions) {
                if (candidate === region) continue
                if (candidate !in ancestors && candidate.world == region.world &&
                    candidate.intersectsColumn(minX, maxX, minZ, maxZ)
                ) {
                    return candidate
                }
                scan(candidate.subRegions)?.let { return it }
            }
            return null
        }
        return scan(roots)
    }

    /**
     * Whether [region] is still attached to the live tree — false once it has
     * gone through [remove] (or was never [add]ed). The region-flags GUI
     * ([RegionFlagsMenu]) re-checks this at click time rather than holding a
     * region deleted mid-session for granted: a `Region` object is otherwise
     * indistinguishable from a live one once detached, since nothing about it
     * marks it as removed.
     */
    fun contains(region: Region): Boolean {
        fun scan(regions: List<Region>): Boolean =
            regions.any { it === region || scan(it.subRegions) }
        return scan(roots)
    }

    /**
     * Every region whose title or any member's name contains [query]
     * (case-insensitive), in depth-first file order — the `/rg locate` search.
     * [memberName] resolves a member uuid to a name, or null if unknown
     * (unresolvable members are simply not searchable, as in the Portal).
     */
    fun search(query: String, memberName: (UUID) -> String?): List<Region> {
        val needle = query.lowercase()
        val found = mutableListOf<Region>()

        fun scan(regions: List<Region>) {
            for (region in regions) {
                if (region.title.lowercase().contains(needle) ||
                    region.members.any { memberName(it)?.lowercase()?.contains(needle) == true }
                ) {
                    found.add(region)
                }
                scan(region.subRegions)
            }
        }
        scan(roots)
        return found
    }
}
