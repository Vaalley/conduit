package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo

/** One relocated dimension's share of the sample, and what it was drawn from. */
data class DimensionSample(
    val role: DimensionRole,
    /**
     * The source chunks compared, in the order they were compared. Kept rather
     * than counted because "the same chunks every time" is the property a
     * rehearsal rests on, and a count cannot show it (ticket 04).
     */
    val sampled: List<ChunkPos>,
    /** Chunks this dimension had to offer, so the report can say what fraction was looked at. */
    val available: Int,
) {
    val compared: Int get() = sampled.size
}

/** What the sampled diff compared, for the operator to check against what they expected. */
data class SampledDiffReport(
    /**
     * What the operator asked for, recorded whether or not there was that much
     * to sample — the size is a decision about how much rehearsal time to spend,
     * so the report has to say which decision was made (merge spec, User Story
     * 19).
     */
    val sampleSize: Int,
    val dimensions: List<DimensionSample>,
) : MergeSection {

    val compared: Int get() = dimensions.sumOf { it.compared }

    fun dimension(role: DimensionRole): DimensionSample = dimensions.first { it.role == role }

    override fun lines(): List<String> = listOf(
        reportLine("sample size", "$sampleSize chunks from each relocated dimension"),
        reportLine(
            "chunks compared",
            if (compared == 0) {
                "none"
            } else {
                "$compared — " + dimensions.joinToString(", ") {
                    "${it.role.id} ${it.compared} of ${it.available}"
                }
            },
        ),
        reportLine(
            "sampled diff",
            if (compared == 0) {
                "nothing was compared, so nothing here says the terrain arrived"
            } else {
                "every sampled chunk matched its source, block for block"
            },
        ),
    )
}

/**
 * Evidence that the terrain actually arrived (merge spec, User Stories 18–19;
 * ticket 04).
 *
 * Everything else that proves the relocation proves it from the relocated data
 * alone. [ChunkRelocation] counts what landed against what was selected, and the
 * audit walks the chunks looking for coordinates that still point into
 * Secondary's old footprint. Both can only find data that is *wrong*. Neither can
 * find data that is *absent*: a chunk that was dropped, truncated or half-written
 * has no stale coordinate to notice and nothing to count itself, so it leaves no
 * trace in either. That is the hole this closes, and it is not a hypothetical one
 * — MCA Selector was found reporting success having relocated nothing at all
 * (ticket 02), and a merge that trusted its exit status would have committed an
 * empty map.
 *
 * So the merge goes back to the source. It picks chunks out of Secondary's own
 * data, loads each one alongside the copy that landed in Primary, and compares
 * them with the offset applied and nothing else forgiven. Three things, and each
 * is evidence of something different:
 *
 * - **The blocks.** A chunk's `sections` — its block states, its biomes, its
 *   light — hold no coordinates at all, so a horizontal move cannot legitimately
 *   change a byte of them and they are compared for exact equality. This is the
 *   block-for-block comparison, and it is what catches a chunk that arrived
 *   truncated rather than not at all.
 * - **The block entities.** Every chest, sign and item frame tile the source
 *   chunk holds must have arrived, with the same identity, at exactly the shifted
 *   position — so a player's chests are proved present and in the right place
 *   rather than merely self-consistent.
 * - **The entities.** The same, for everything standing in the chunk. Entity data
 *   is stored in a folder of its own, and [ChunkRelocation]'s count is taken from
 *   the terrain folder, so entity data that never arrived is exactly the kind of
 *   absence nothing else here would see.
 *
 * What it deliberately does not compare is everything a relocation legitimately
 * rewrites beyond a block entity's own position: structure starts and references,
 * block and fluid ticks, brain memories, point-of-interest records. Those are the
 * audit's subject, and re-deriving what the tool should have written for each of
 * them would be reimplementing the relocation the merge deliberately does not
 * perform itself — a comparison that could only ever disagree with the tool for
 * reasons of its own.
 *
 * A single mismatch throws, which abandons the whole merge with the staging
 * directory cleared and the live save untouched (see [MergeStaging]).
 */
class SampledDiff(
    /** The live save, holding Secondary's own chunk data — the merge only ever copies it. */
    private val levelDir: Path,
    /** Primary's dimensions as [ChunkRelocation] has just rebuilt them inside the staging area. */
    private val stagedLevelDir: Path,
    private val offset: MergeOffset,
    /** How many chunks of each relocated dimension the operator asked to have compared. */
    private val sampleSize: Int,
    /**
     * How much of Secondary was carried at all (ticket 13). The sample is drawn
     * from what the merge undertook to move, never from the whole save: a chunk
     * the border kept out is *meant* not to have arrived, and comparing it
     * against the empty space where it was never going to be would fail the merge
     * over the clip working.
     */
    private val border: SecondaryBorder,
) {

    fun verify(): SampledDiffReport = SampledDiffReport(
        sampleSize = sampleSize,
        dimensions = MergeGeometry.RELOCATED_ROLES.mapNotNull(::verify),
    )

    /**
     * One dimension sampled and compared, or null when Secondary never generated
     * it — the same silence [ChunkRelocation] keeps about a nether nobody
     * entered, rather than a line claiming nought of nought.
     */
    private fun verify(role: DimensionRole): DimensionSample? {
        val from = Footprint.storageFolder(levelDir, WorldLayout.SECONDARY.dimension(role))
        val into = Footprint.storageFolder(stagedLevelDir, WorldLayout.PRIMARY.dimension(role))
        val inventory = chunksIn(from.resolve(TERRAIN))
        if (inventory.isEmpty()) return null

        val sampled = sample(inventory)
        for (chunk in load(role, from, into, sampled)) {
            val difference = difference(role, chunk) ?: continue
            throw IllegalStateException(
                "the relocated copy of ${describe(chunk.at)} of Secondary's ${role.id} is not its " +
                    "source: $difference. The merge compares a sample of the relocated chunks against " +
                    "the chunks they came from, because a chunk that never arrived is invisible to " +
                    "everything that only reads the relocated data. Nothing has been moved into place.",
            )
        }
        return DimensionSample(role, sampled, inventory.size)
    }

    // ---- choosing the sample ------------------------------------------------

    /**
     * [sampleSize] chunks spread evenly across [inventory], both ends included.
     *
     * Two properties, and the merge needs both. It is **reproducible**: the
     * inventory is the save's own chunk positions in a fixed order and the picks
     * are arithmetic on that order, so a rehearsal and the real run compare the
     * same chunks and their reports can be read side by side. There is no clock
     * and no random source anywhere in it, seeded or otherwise. And it is
     * **spread**: the inventory is ordered by region file and then by position
     * within it, so evenly spaced picks walk the whole footprint instead of
     * proving a corner of it — which matters because the failure being looked for
     * is a region file that never landed, and a sample taken from the first file
     * would never look at the last one.
     *
     * The cost of a regular stride is that it could in principle line up with a
     * periodic fault. Raising the sample size changes every pick rather than
     * adding to them, so a second run at a different size is a genuinely
     * different check rather than a longer version of the same one.
     */
    private fun sample(inventory: List<ChunkPos>): List<ChunkPos> {
        if (sampleSize <= 0) return emptyList()
        if (sampleSize >= inventory.size) return inventory
        // One chunk is taken from the middle rather than the start, so the
        // smallest sample anyone can ask for is still not a corner.
        if (sampleSize == 1) return listOf(inventory[(inventory.size - 1) / 2])
        val last = inventory.size - 1
        val steps = sampleSize - 1
        // Integer arithmetic with half a step added before the division, so the
        // picks are the evenly spaced ones rounded rather than always rounded down.
        return (0..steps).map { inventory[(it * last + steps / 2) / steps] }
    }

    /**
     * Every chunk position stored under [folder], ordered by region file and then
     * by position within it.
     *
     * Read from the region files' headers, as [ChunkRelocation] counts them and
     * for the same reason: it needs no chunk decompressed, so choosing the sample
     * costs a few kilobytes per file rather than a pass over the whole map, and a
     * file that is not chunk data at all yields nothing instead of throwing.
     */
    private fun chunksIn(folder: Path): List<ChunkPos> {
        if (!Files.isDirectory(folder)) return emptyList()
        val files = Files.newDirectoryStream(folder, "r.*.mca").use { entries ->
            entries.mapNotNull { file -> RegionFilePos.parse(file.name)?.let { it to file } }
                // What the border kept out was never going to arrive, so it is not
                // in the inventory the sample is drawn from and not in the count
                // the report states the sample as a fraction of.
                .filter { border.keeps(it.first) }
                .sortedWith(compareBy({ it.first.x }, { it.first.z }))
        }
        return files.flatMap { (at, file) -> positionsIn(file, at) }
    }

    /**
     * The chunk positions [file] has data for. Its first 4096 bytes are 1024
     * four-byte entries in raster order — X fastest, then Z — and an entry is all
     * zero exactly when nothing is stored at that position.
     */
    private fun positionsIn(file: Path, at: RegionFilePos): List<ChunkPos> {
        val header = ByteArray(LOCATIONS_BYTES)
        Files.newInputStream(file).use {
            if (it.readNBytes(header, 0, LOCATIONS_BYTES) < LOCATIONS_BYTES) return emptyList()
        }
        val found = mutableListOf<ChunkPos>()
        for (entry in 0 until CHUNKS_PER_REGION_FILE) {
            val start = entry * LOCATION_BYTES
            if ((0 until LOCATION_BYTES).all { header[start + it].toInt() == 0 }) continue
            found += ChunkPos(
                at.x * CHUNKS_PER_REGION + entry % CHUNKS_PER_REGION,
                at.z * CHUNKS_PER_REGION + entry / CHUNKS_PER_REGION,
            )
        }
        return found
    }

    // ---- loading both sides -------------------------------------------------

    /** One sampled chunk as it was and as it arrived, which is all a comparison needs. */
    private class SampledChunk(
        val at: ChunkPos,
        val landedAt: ChunkPos,
        val source: CompoundTag?,
        val landed: CompoundTag?,
        val sourceEntities: CompoundTag?,
        val landedEntities: CompoundTag?,
    )

    private fun load(
        role: DimensionRole,
        from: Path,
        into: Path,
        sampled: List<ChunkPos>,
    ): List<SampledChunk> {
        val secondary = WorldLayout.SECONDARY.dimension(role)
        val primary = WorldLayout.PRIMARY.dimension(role)
        val landedAt = sampled.associateWith { moved(it, role) }

        val source = read(from.resolve(TERRAIN), secondary, TERRAIN_TYPE, sampled)
        val sourceEntities = read(from.resolve(ENTITIES), secondary, ENTITIES, sampled)
        val landed = read(into.resolve(TERRAIN), primary, TERRAIN_TYPE, landedAt.values.toList())
        val landedEntities = read(into.resolve(ENTITIES), primary, ENTITIES, landedAt.values.toList())

        return sampled.map {
            val to = landedAt.getValue(it)
            SampledChunk(it, to, source[it], landed[to], sourceEntities[it], landedEntities[to])
        }
    }

    /** Where [at] should be once [role] has moved. */
    private fun moved(at: ChunkPos, role: DimensionRole): ChunkPos = ChunkPos(
        at.x + offset.shiftX(role) / BLOCKS_PER_CHUNK,
        at.z + offset.shiftZ(role) / BLOCKS_PER_CHUNK,
    )

    /**
     * [positions]' chunk data, read through the server's own [RegionFile] so that
     * the sectors, the header and the compression are the ones a booting server
     * would read rather than this class's idea of them.
     *
     * Region files are opened once each rather than once per chunk, and only when
     * they are already there: [RegionFile] creates what it is pointed at, and a
     * merge that invented an empty region file while checking its own work would
     * stage a file nothing ever landed in.
     */
    private fun read(
        folder: Path,
        dimension: ResourceKey<Level>,
        type: String,
        positions: List<ChunkPos>,
    ): Map<ChunkPos, CompoundTag> {
        if (positions.isEmpty() || !Files.isDirectory(folder)) return emptyMap()
        val found = LinkedHashMap<ChunkPos, CompoundTag>()
        val byFile = positions.groupBy { RegionFilePos(it.x shr REGION_SHIFT, it.z shr REGION_SHIFT) }
        for ((at, inFile) in byFile) {
            val file = folder.resolve(at.fileName)
            if (Files.notExists(file)) continue
            RegionFile(RegionStorageInfo(LEVEL_NAME, dimension, type), file, folder, false).use { region ->
                for (position in inFile) {
                    if (!region.hasChunk(position)) continue
                    region.getChunkDataInputStream(position)?.use { found[position] = NbtIo.read(it) }
                }
            }
        }
        return found
    }

    // ---- the comparison itself ----------------------------------------------

    /** What differs between a chunk and the copy of it that arrived, or null when nothing does. */
    private fun difference(role: DimensionRole, chunk: SampledChunk): String? {
        val source = chunk.source ?: return "its source cannot be read out of Secondary's own data, " +
            "though the region file's header says it is there"

        // A chunk vanilla never finished is one the merge deliberately leaves at
        // Secondary's frontier, so for those the evidence wanted is the opposite:
        // that nothing arrived (merge spec, User Story 14).
        if (!isFinished(source)) {
            if (chunk.landed == null) return null
            return "vanilla never finished it — its status is \"${status(source)}\" — so it should have " +
                "stayed at Secondary's frontier, and instead a copy of it arrived at " +
                "${describe(chunk.landedAt)}"
        }

        val landed = chunk.landed
            ?: return "it never arrived: nothing at all is stored at ${describe(chunk.landedAt)}, where " +
                "the relocation was to put it"

        return landedInTheWrongPlace(chunk, landed)
            ?: blocksDiffer(source, landed)
            ?: difference(
                "block entities",
                blockEntities(source, offset.shiftX(role), offset.shiftZ(role)),
                blockEntities(landed, 0, 0),
            )
            ?: difference(
                "entities",
                entities(
                    chunk.sourceEntities,
                    source,
                    offset.shiftX(role).toDouble(),
                    offset.shiftZ(role).toDouble(),
                ),
                entities(chunk.landedEntities, landed, 0.0, 0.0),
            )
    }

    /**
     * The chunk's own account of where it is. It is the one coordinate a
     * relocation cannot get away with leaving alone, so it is checked before
     * anything inside the chunk is.
     */
    private fun landedInTheWrongPlace(chunk: SampledChunk, landed: CompoundTag): String? {
        val fields = fieldsOf(landed).of
        val x = fields.getIntOr(CHUNK_X, Int.MIN_VALUE)
        val z = fields.getIntOr(CHUNK_Z, Int.MIN_VALUE)
        if (x == chunk.landedAt.x && z == chunk.landedAt.z) return null
        return "the chunk stored at ${describe(chunk.landedAt)} says it is ${describe(ChunkPos(x, z))}"
    }

    /**
     * The block-for-block half. A section holds block states, biomes and light and
     * not one coordinate, so a horizontal move has no business changing any of it
     * and exact equality is the honest test.
     */
    private fun blocksDiffer(source: CompoundTag, landed: CompoundTag): String? {
        val from = fieldsOf(source)
        val to = fieldsOf(landed)
        val was = blockBearing(from)
        val now = blockBearing(to)
        val missing = (was.keys - now.keys).sorted()
        if (missing.isNotEmpty()) {
            return "its blocks did not all arrive: section${plural(missing.size)} y " +
                "${missing.joinToString(", ")} of the source ${if (missing.size == 1) "is" else "are"} " +
                "not there"
        }
        val extra = (now.keys - was.keys).sorted()
        if (extra.isNotEmpty()) {
            return "it has blocks the source does not: section${plural(extra.size)} y " +
                "${extra.joinToString(", ")} arrived from nowhere"
        }
        val differing = was.keys.sorted().firstOrNull { was[it] != now[it] } ?: return null
        return "its blocks differ: section y $differing is not the section the source has there"
    }

    /**
     * Every block entity as `<id> at <x>, <y>, <z>`, with [shiftX] and [shiftZ]
     * added — so the source's list, shifted, is literally the list the relocated
     * chunk has to have. Sorted, because a relocation is free to write them back
     * in any order and only which ones are where is being claimed.
     */
    private fun blockEntities(chunk: CompoundTag, shiftX: Int, shiftZ: Int): List<String> =
        fieldsOf(chunk).let { compounds(it.of.getListOrEmpty(it.blockEntities)) }.map {
            "${it.getStringOr(ID, UNNAMED)} at ${it.getIntOr("x", 0) + shiftX}, " +
                "${it.getIntOr("y", 0)}, ${it.getIntOr("z", 0) + shiftZ}"
        }.sorted()

    /**
     * The same for the entities standing in the chunk, out of the entity storage
     * beside the terrain.
     *
     * A chunk with no entity data at all and a chunk whose entity data lists
     * nothing are the same claim about the chunk — nothing stands there — so both
     * come out as an empty list rather than as a difference between the two
     * spellings.
     */
    private fun entities(
        chunk: CompoundTag?,
        terrain: CompoundTag,
        shiftX: Double,
        shiftZ: Double,
    ): List<String> =
        // Entity storage moved out of the terrain chunk in 1.17. A chunk older
        // than that keeps its entities in `Level.Entities` and has no entry in
        // the entities folder at all, so the terrain chunk is where they are.
        compounds(chunk?.getListOrEmpty(ENTITY_LIST) ?: fieldsOf(terrain).of.getListOrEmpty(ENTITY_LIST)).map {
            val at = it.getListOrEmpty(ENTITY_POSITION)
            "${it.getStringOr(ID, UNNAMED)} at ${at.getDoubleOr(0, 0.0) + shiftX}, " +
                "${at.getDoubleOr(1, 0.0)}, ${at.getDoubleOr(2, 0.0) + shiftZ}"
        }.sorted()

    /**
     * What is not there and what is there instead, named — an operator reading a
     * failed merge at 2am needs the thing itself, not a count of things.
     */
    private fun difference(what: String, expected: List<String>, found: List<String>): String? {
        if (expected == found) return null
        val missing = expected.filterNot(found::contains)
        val extra = found.filterNot(expected::contains)
        return "its $what differ: " + when {
            missing.isNotEmpty() -> "${missing.first()} never arrived${andMore(missing.size - 1)}"
            extra.isNotEmpty() -> "${extra.first()} arrived, which the source does not have" +
                andMore(extra.size - 1)
            else -> "the source has ${expected.size} of them and ${found.size} arrived"
        }
    }

    private fun andMore(others: Int): String = if (others > 0) " (and $others more)" else ""

    private fun compounds(list: ListTag): List<CompoundTag> =
        (0 until list.size).map(list::getCompoundOrEmpty)

    private fun status(chunk: CompoundTag): String = fieldsOf(chunk).of.getStringOr(STATUS, "")

    /**
     * Where a chunk keeps its own fields, and what it calls two of them.
     *
     * Secondary's chunks are a mixture of DataVersions — vanilla upgrades one
     * only when it loads it, so ground nobody has walked since before the Portal
     * cutover is still in the shape the version that generated it wrote. A chunk
     * older than 1.18 keeps everything under `Level` and spells its section and
     * block-entity lists differently.
     *
     * Reading only the modern shape does not fail loudly on one of those, which
     * is what makes this worth a type rather than a special case: an absent
     * section list compares equal to an absent section list, so the diff would
     * have passed every old chunk without ever looking inside it. The rehearsal
     * caught it the other way round — an absent `Status` read as "unfinished",
     * and the merge refused over a chunk that was fine.
     */
    private class Fields(val of: CompoundTag, val sections: String, val blockEntities: String)

    /**
     * The sections that actually hold blocks, by the height they sit at.
     *
     * A save carries sections that hold nothing but their own index — the live
     * Secondary has chunks whose lowest section is exactly `{Y: -1}`, with no
     * palette, no block states and no light. Vanilla reads an absent section as
     * air and so reads one of these as air too, and the relocation drops them on
     * the way through.
     *
     * That is a normalisation rather than a loss, and comparing raw section
     * *lists* could not tell the two apart: it read one stub going missing as
     * "its blocks did not all arrive" and refused a merge over ground that is not
     * there in either copy. Keying by Y and comparing only what carries blocks
     * says the thing actually meant — every block the source had is a block that
     * arrived, at the height it was at — and it still fails, loudly, on a section
     * that holds something and goes missing.
     */
    private fun blockBearing(fields: Fields): Map<Int, CompoundTag> =
        compounds(fields.of.getListOrEmpty(fields.sections))
            .filter { section -> BLOCK_DATA.any(section::contains) }
            .associateBy { it.getIntOr(SECTION_Y, Int.MIN_VALUE) }

    private fun fieldsOf(chunk: CompoundTag): Fields {
        val level = chunk.getCompoundOrEmpty(LEVEL)
        return if (level.isEmpty) {
            Fields(chunk, SECTIONS, BLOCK_ENTITIES)
        } else {
            Fields(level, LEGACY_SECTIONS, LEGACY_BLOCK_ENTITIES)
        }
    }

    /**
     * Whether vanilla finished generating this chunk, which is the same question
     * [McaSelector.select] asks of it — asked here of the chunk's own NBT, where
     * the namespace vanilla writes is still on the front of the answer.
     */
    private fun isFinished(chunk: CompoundTag): Boolean =
        status(chunk).removePrefix(VANILLA_NAMESPACE) == McaSelector.FULL_STATUS

    private fun describe(at: ChunkPos): String = "chunk ${at.x}, ${at.z}"

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    private companion object {
        /** The folder holding the blocks, and the storage type [RegionFile] knows it by. */
        const val TERRAIN = "region"
        const val TERRAIN_TYPE = "chunk"

        /** The folder holding what stands on them; its own storage type is its own name. */
        const val ENTITIES = "entities"

        const val SECTIONS = "sections"

        /** Where a chunk older than 1.18 keeps everything, and its own two spellings. */
        const val LEVEL = "Level"
        const val LEGACY_SECTIONS = "Sections"
        const val LEGACY_BLOCK_ENTITIES = "TileEntities"

        /**
         * What makes a section a section rather than a placeholder — in either
         * layout, since both spellings turn up in one save.
         */
        val BLOCK_DATA = listOf("block_states", "BlockStates", "Palette")
        const val SECTION_Y = "Y"
        const val BLOCK_ENTITIES = "block_entities"
        const val ENTITY_LIST = "Entities"
        const val ENTITY_POSITION = "Pos"
        const val CHUNK_X = "xPos"
        const val CHUNK_Z = "zPos"
        const val STATUS = "Status"
        const val ID = "id"
        const val UNNAMED = "an entity with no id"
        const val VANILLA_NAMESPACE = "minecraft:"

        /** Only ever used to label the region files this opens, never to find them. */
        const val LEVEL_NAME = "world"

        const val BLOCKS_PER_CHUNK = 16
        const val CHUNKS_PER_REGION = 32

        /** log2 of [CHUNKS_PER_REGION] — the shift from a chunk coordinate to its region file's. */
        const val REGION_SHIFT = 5

        const val CHUNKS_PER_REGION_FILE = CHUNKS_PER_REGION * CHUNKS_PER_REGION
        const val LOCATION_BYTES = 4
        const val LOCATIONS_BYTES = CHUNKS_PER_REGION_FILE * LOCATION_BYTES
    }
}
