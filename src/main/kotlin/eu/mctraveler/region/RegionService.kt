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

    fun save() {
        file.parent?.let(Files::createDirectories)
        Files.writeString(file, RegionStore.serialize(roots))
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

    /**
     * The deepest region containing the block position in the given World
     * (legacy world string), or null — sub-regions win over their parents.
     */
    fun regionAt(world: String, x: Int, y: Int, z: Int): Region? {
        var candidates: List<Region> = roots.filter { it.world == world }
        var found: Region? = null
        while (true) {
            val match = candidates.firstOrNull { it.contains(x, y, z) } ?: return found
            found = match
            candidates = match.subRegions
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
