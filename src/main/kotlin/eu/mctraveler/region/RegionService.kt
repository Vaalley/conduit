package eu.mctraveler.region

import com.google.gson.JsonPrimitive
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
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
 *
 * Queries run off a chunk-bucketed spatial index: every region is listed under
 * each 16×16 column its x/z footprint touches, per world. [regionAt] pays one
 * hash probe plus a cuboid test on the handful of regions sharing the chunk
 * instead of a full tree scan — protection and environment code call it for
 * every block of every explosion, every fluid spread step, and every entity
 * damage event. The index rebuilds lazily after any structural change: [save],
 * [add]/[remove], and any in-place mutation of [roots] or a live region's
 * `subRegions` (both lists report through [DirtyTrackingList]).
 */
class RegionService(private val file: Path) {

    private var indexDirty = true
    private var index: Index = Index(emptyMap(), emptyMap())

    /** The root regions, in file order. Sub-regions hang off their parents. */
    val roots: MutableList<Region> = DirtyTrackingList<Region> { indexDirty = true }
        .also { roots ->
            if (Files.exists(file)) RegionStore.parse(Files.readString(file)).forEach(roots::add)
        }

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
        indexDirty = true
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

    /**
     * Stable id used by passport records. Unlike a tree position, it survives
     * deleting or reordering unrelated regions.
     */
    fun stableIdOf(region: Region): String {
        val existing = stableIdOfStored(region)
        if (existing != null) return existing

        val id = UUID.randomUUID().toString()
        region.metadata[PASSPORT_ID_KEY] = JsonPrimitive(id)
        save()
        return id
    }

    /** Resolves a stable passport id, or null when the region was deleted. */
    fun byStableId(id: String): Region? = index().stable[id]

    private fun index(): Index {
        if (indexDirty) rebuildIndex()
        return index
    }

    private fun rebuildIndex() {
        val chunks = HashMap<String, Long2ObjectOpenHashMap<MutableList<Region>>>()
        val stable = HashMap<String, Region>()
        val onTreeChanged: () -> Unit = { indexDirty = true }

        fun walk(regions: List<Region>) {
            for (region in regions) {
                region.onTreeChanged = onTreeChanged
                val buckets = chunks.getOrPut(region.world) { Long2ObjectOpenHashMap() }
                for (cx in (region.minX shr 4)..(region.maxX shr 4)) {
                    for (cz in (region.minZ shr 4)..(region.maxZ shr 4)) {
                        buckets.getOrPut(chunkKey(cx, cz)) { ArrayList() }.add(region)
                    }
                }
                stableIdOfStored(region)?.let { stable[it] = region }
                walk(region.subRegions)
            }
        }
        walk(roots)

        indexDirty = false
        index = Index(chunks, stable)
    }

    /**
     * The deepest region containing the block position in the given World
     * (legacy world string), or null — sub-regions win over their parents.
     *
     * Probes the chunk index: one bucket lookup filters the tree to the regions
     * whose footprint covers this column, then deepest containment decides. A
     * world with no regions misses the map — explosions, fluid spread and fire
     * ticks in unprotected space pay a single hash probe.
     */
    fun regionAt(world: String, x: Int, y: Int, z: Int): Region? {
        val candidates = index().chunks[world]
            ?.get(chunkKey(x shr 4, z shr 4)) ?: return null
        var found: Region? = null
        var foundDepth = -1
        for (region in candidates) {
            if (!region.contains(x, y, z)) continue
            var depth = 0
            var parent = region.parent
            while (parent != null) {
                depth++
                parent = parent.parent
            }
            if (depth > foundDepth) {
                found = region
                foundDepth = depth
            }
        }
        return found
    }

    /**
     * Whether any region's x/z footprint intersects the given column range in
     * the given World — a bounding-box probe for callers about to scan many
     * blocks (explosions, piston pushes). When it answers false, every block
     * in the range is outside protection without one [regionAt] each; when it
     * answers true the caller falls back to per-block checks.
     */
    fun anyRegionIntersecting(
        world: String,
        minX: Int,
        maxX: Int,
        minZ: Int,
        maxZ: Int,
    ): Boolean {
        val worldIndex = index().chunks[world] ?: return false
        val minCX = minX shr 4
        val maxCX = maxX shr 4
        val minCZ = minZ shr 4
        val maxCZ = maxZ shr 4
        for (cx in minCX..maxCX) {
            for (cz in minCZ..maxCZ) {
                val bucket = worldIndex.get(chunkKey(cx, cz)) ?: continue
                for (region in bucket) {
                    if (region.intersectsColumn(minX, maxX, minZ, maxZ)) return true
                }
            }
        }
        return false
    }

    private data class Index(
        val chunks: Map<String, Long2ObjectOpenHashMap<MutableList<Region>>>,
        val stable: Map<String, Region>,
    )

    private fun stableIdOfStored(region: Region): String? =
        region.metadata[PASSPORT_ID_KEY]
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.takeIf(String::isNotEmpty)

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

    private companion object {
        const val PASSPORT_ID_KEY = "passport-id"

        /**
         * Packs a chunk x/z pair into one long — the same layout ChunkPos uses,
         * kept local so the service stays free of game classes.
         */
        private fun chunkKey(x: Int, z: Int): Long =
            (x.toLong() and 0x3FFFFF) or ((z.toLong() and 0x3FFFFF) shl 22)
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

/**
 * A `MutableList` that reports every structural mutation — element or iterator
 * adds, removes and reorderings — to [onMutate]. Lets the spatial index notice
 * callers that mutate `roots`/`subRegions` in place rather than going through
 * [RegionService.add]/[remove]/[save].
 */
internal class DirtyTrackingList<E> private constructor(
    private val backing: ArrayList<E>,
    private val onMutate: () -> Unit,
) : MutableList<E> by backing {

    constructor(onMutate: () -> Unit) : this(ArrayList(), onMutate)

    override fun add(element: E): Boolean =
        backing.add(element).also { onMutate() }

    override fun add(index: Int, element: E) {
        backing.add(index, element)
        onMutate()
    }

    override fun addAll(elements: Collection<E>): Boolean =
        backing.addAll(elements).also { if (it) onMutate() }

    override fun addAll(index: Int, elements: Collection<E>): Boolean =
        backing.addAll(index, elements).also { if (it) onMutate() }

    override fun clear() {
        if (isNotEmpty()) {
            backing.clear()
            onMutate()
        }
    }

    override fun iterator(): MutableIterator<E> = iterator(backing.iterator())

    override fun listIterator(): MutableListIterator<E> = listIterator(backing.listIterator())

    override fun listIterator(index: Int): MutableListIterator<E> =
        listIterator(backing.listIterator(index))

    override fun remove(element: E): Boolean =
        backing.remove(element).also { if (it) onMutate() }

    override fun removeAll(elements: Collection<E>): Boolean =
        backing.removeAll(elements).also { if (it) onMutate() }

    override fun removeAt(index: Int): E =
        backing.removeAt(index).also { onMutate() }

    override fun removeIf(filter: java.util.function.Predicate<in E>): Boolean =
        backing.removeIf(filter).also { if (it) onMutate() }

    override fun retainAll(elements: Collection<E>): Boolean =
        backing.retainAll(elements).also { if (it) onMutate() }

    override fun set(index: Int, element: E): E =
        backing.set(index, element).also { onMutate() }

    override fun sort(c: Comparator<in E>) {
        backing.sortWith(c)
        onMutate()
    }

    private fun iterator(delegate: MutableIterator<E>) = object : MutableIterator<E> {
        override fun hasNext(): Boolean = delegate.hasNext()
        override fun next(): E = delegate.next()
        override fun remove() {
            delegate.remove()
            onMutate()
        }
    }

    private fun listIterator(delegate: MutableListIterator<E>) = object : MutableListIterator<E> {
        override fun hasNext(): Boolean = delegate.hasNext()
        override fun next(): E = delegate.next()
        override fun nextIndex(): Int = delegate.nextIndex()
        override fun hasPrevious(): Boolean = delegate.hasPrevious()
        override fun previous(): E = delegate.previous()
        override fun previousIndex(): Int = delegate.previousIndex()
        override fun add(element: E) {
            delegate.add(element)
            onMutate()
        }
        override fun remove() {
            delegate.remove()
            onMutate()
        }
        override fun set(element: E) {
            delegate.set(element)
            onMutate()
        }
    }
}
