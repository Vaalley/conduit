package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo

/**
 * Chunk data a test can hand to the real relocation tool and read back
 * afterwards.
 *
 * These are *real* region files — written and read through Minecraft's own
 * [RegionFile], so the sector layout, the header and the compression are the
 * server's rather than a test's idea of them. That matters more here than in the
 * other importer fixtures: MCA Selector parses what it is given at the chunk's
 * own `DataVersion`, so chunk data a test invented a format for would prove
 * nothing about the tool the merge actually runs (merge spec, "Testing
 * Decisions").
 *
 * The chunks themselves are as small as a chunk can be and still be a chunk. The
 * merge relocates them by rewriting their coordinates, and a chunk carrying one
 * section and one block entity exercises exactly as much of that as a chunk
 * carrying a real player's base — the block-for-block comparison of real terrain
 * is a different ticket's evidence.
 */
object SyntheticChunks {

    /**
     * The status vanilla gives a chunk it has finished generating. Anything else
     * is a frontier chunk the merge drops rather than moves (merge spec, User
     * Story 14).
     */
    const val FULL = "minecraft:full"

    /** A chunk vanilla started and never finished — the frontier the merge leaves behind. */
    const val PROTO = "minecraft:noise"

    /** This server's chunk format version, so the tool dispatches the way it will in production. */
    val dataVersion: Int get() = SharedConstants.getCurrentVersion().dataVersion().version()

    /**
     * One chunk's worth of terrain, entity and point-of-interest data.
     *
     * [beds] are absolute block positions inside this chunk holding a bed rather
     * than stone. They exist because the merge's respawn cross-check has to find
     * a real block at a real coordinate: a respawn point and the bed it names
     * travel by two different routes, and only an actual bed in an actual
     * section proves they arrived at the same place.
     */
    data class Chunk(
        val x: Int,
        val z: Int,
        val status: String = FULL,
        val beds: List<BlockPos> = emptyList(),
    )

    /**
     * Writes [chunks] into [dimension]'s `region`, `entities` and `poi` folders
     * under [levelDir]. All three, always: a merge that moved terrain and left a
     * village's point-of-interest records behind would pass a terrain-only test.
     */
    fun write(levelDir: Path, dimension: ResourceKey<Level>, chunks: List<Chunk>) {
        val storage = Footprint.storageFolder(levelDir, dimension)
        writeFolder(storage.resolve("region"), dimension, "chunk", chunks, ::terrain)
        writeFolder(storage.resolve("entities"), dimension, "entities", chunks, ::entities)
        writeFolder(storage.resolve("poi"), dimension, "poi", chunks, ::pointsOfInterest)
    }

    /** Every chunk present in the region files under [folder], by position. */
    fun read(folder: Path, type: String, dimension: ResourceKey<Level>): Map<ChunkPos, CompoundTag> {
        if (!Files.isDirectory(folder)) return emptyMap()
        val found = LinkedHashMap<ChunkPos, CompoundTag>()
        val files = Files.newDirectoryStream(folder, "r.*.mca").use { it.sortedBy(Path::toString) }
        for (file in files) {
            val position = RegionFilePos.parse(file.fileName.toString()) ?: continue
            RegionFile(RegionStorageInfo("world", dimension, type), file, folder, false).use { region ->
                for (localZ in 0 until CHUNKS_PER_REGION) {
                    for (localX in 0 until CHUNKS_PER_REGION) {
                        val at = ChunkPos(
                            position.x * CHUNKS_PER_REGION + localX,
                            position.z * CHUNKS_PER_REGION + localZ,
                        )
                        if (!region.hasChunk(at)) continue
                        region.getChunkDataInputStream(at)?.use { found[at] = NbtIo.read(it) }
                    }
                }
            }
        }
        return found
    }

    /** Just the positions, for the common case of asking where things landed. */
    fun positions(folder: Path, type: String, dimension: ResourceKey<Level>): Set<ChunkPos> =
        read(folder, type, dimension).keys

    private fun writeFolder(
        folder: Path,
        dimension: ResourceKey<Level>,
        type: String,
        chunks: List<Chunk>,
        content: (Chunk) -> CompoundTag,
    ) {
        if (chunks.isEmpty()) return
        Files.createDirectories(folder)
        // One RegionFile per file, opened once: a region file is a sector
        // allocator, and reopening it per chunk would be a different test of it.
        for ((position, inFile) in chunks.groupBy { RegionFilePos(it.x shr REGION_SHIFT, it.z shr REGION_SHIFT) }) {
            val file = folder.resolve(position.fileName)
            RegionFile(RegionStorageInfo("world", dimension, type), file, folder, false).use { region ->
                for (chunk in inFile) {
                    region.getChunkDataOutputStream(ChunkPos(chunk.x, chunk.z)).use {
                        NbtIo.write(content(chunk), it)
                    }
                }
            }
        }
    }

    /** A chunk of terrain: one stone section, one block entity, at its own coordinates. */
    private fun terrain(chunk: Chunk): CompoundTag {
        val tag = CompoundTag()
        tag.putInt("DataVersion", dataVersion)
        tag.putInt("xPos", chunk.x)
        tag.putInt("yPos", MIN_SECTION)
        tag.putInt("zPos", chunk.z)
        tag.putString("Status", chunk.status)
        tag.putLong("LastUpdate", 0L)
        tag.putLong("InhabitedTime", 0L)

        val sections = ListTag()
        val beds = chunk.beds.groupBy { it.y shr SECTION_SHIFT }
        for (sectionY in (beds.keys + MIN_SECTION).sorted()) {
            sections.add(section(sectionY, beds[sectionY].orEmpty()))
        }
        tag.put("sections", sections)

        // A chest at the chunk's own north-west corner. Its x/z are absolute, so
        // it is the simplest thing in a chunk that a relocation has to rewrite.
        val chest = CompoundTag()
        chest.putString("id", "minecraft:chest")
        chest.putInt("x", chunk.x * BLOCKS_PER_CHUNK)
        chest.putInt("y", 64)
        chest.putInt("z", chunk.z * BLOCKS_PER_CHUNK)
        chest.put("Items", ListTag())
        val blockEntities = ListTag()
        blockEntities.add(chest)
        tag.put("block_entities", blockEntities)

        tag.put("block_ticks", ListTag())
        tag.put("fluid_ticks", ListTag())
        tag.put("Heightmaps", CompoundTag())
        tag.put("structures", CompoundTag().also {
            it.put("starts", CompoundTag())
            it.put("References", CompoundTag())
        })
        return tag
    }

    /**
     * One section of sixteen cubic metres: stone, with a bed wherever [beds]
     * asks for one.
     *
     * A section of a single block state stores no indices at all — there is
     * nothing to tell apart — which is why the plain sections below carry a
     * palette and no `data`. As soon as there are two states every one of the
     * 4096 blocks needs an index into the palette, at the four bits a section
     * palette is never indexed with fewer of, sixteen to a long and never
     * straddling one.
     */
    private fun section(y: Int, beds: List<BlockPos>): CompoundTag {
        val palette = ListTag()
        palette.add(CompoundTag().also { it.putString("Name", "minecraft:stone") })
        val blockStates = CompoundTag()
        if (beds.isNotEmpty()) {
            palette.add(CompoundTag().also { it.putString("Name", BED) })
            val data = LongArray(BLOCKS_PER_SECTION / ENTRIES_PER_LONG)
            for (bed in beds) {
                val index = ((bed.y and BLOCK_MASK) shl (AXIS_BITS * 2)) or
                    ((bed.z and BLOCK_MASK) shl AXIS_BITS) or (bed.x and BLOCK_MASK)
                data[index / ENTRIES_PER_LONG] = data[index / ENTRIES_PER_LONG] or
                    (1L shl (index % ENTRIES_PER_LONG * PALETTE_BITS))
            }
            blockStates.putLongArray("data", data)
        }
        blockStates.put("palette", palette)
        return CompoundTag().apply {
            putByte("Y", y.toByte())
            put("block_states", blockStates)
        }
    }

    /** One cow, standing in the middle of the chunk. */
    private fun entities(chunk: Chunk): CompoundTag {
        val tag = CompoundTag()
        tag.putInt("DataVersion", dataVersion)
        tag.putIntArray("Position", intArrayOf(chunk.x, chunk.z))
        val cow = CompoundTag()
        cow.putString("id", "minecraft:cow")
        val position = ListTag()
        position.add(DoubleTag.valueOf(chunk.x * BLOCKS_PER_CHUNK + 8.0))
        position.add(DoubleTag.valueOf(64.0))
        position.add(DoubleTag.valueOf(chunk.z * BLOCKS_PER_CHUNK + 8.0))
        cow.put("Pos", position)
        cow.putIntArray("UUID", intArrayOf(0, 0, 0, chunk.x * 31 + chunk.z))
        val list = ListTag()
        list.add(cow)
        tag.put("Entities", list)
        return tag
    }

    /** One villager's bed, so the merge has a point-of-interest record to carry. */
    private fun pointsOfInterest(chunk: Chunk): CompoundTag {
        val tag = CompoundTag()
        tag.putInt("DataVersion", dataVersion)
        val record = CompoundTag()
        record.putIntArray("pos", intArrayOf(chunk.x * BLOCKS_PER_CHUNK, 64, chunk.z * BLOCKS_PER_CHUNK))
        record.putString("type", "minecraft:home")
        record.putInt("free_tickets", 1)
        val records = ListTag()
        records.add(record)
        val section = CompoundTag()
        section.put("Records", records)
        section.putBoolean("Valid", true)
        val sections = CompoundTag()
        sections.put(SECTION_KEY, section)
        tag.put("Sections", sections)
        return tag
    }

    private const val CHUNKS_PER_REGION = 32

    /** log2 of [CHUNKS_PER_REGION] — the shift from a chunk coordinate to its region file's. */
    private const val REGION_SHIFT = 5

    private const val BLOCKS_PER_CHUNK = 16

    /** Section y 4, which is blocks 64…79 — where everything above is put. */
    private const val SECTION_KEY = "4"

    /** The bottom section of a 26.2 overworld chunk, in sections. */
    private const val MIN_SECTION = -4

    /** The bed a [Chunk] puts down, and the arithmetic of packing a section's block indices. */
    private const val BED = "minecraft:red_bed"
    private const val SECTION_SHIFT = 4
    private const val BLOCK_MASK = 15
    private const val AXIS_BITS = 4
    private const val BLOCKS_PER_SECTION = 4096
    private const val PALETTE_BITS = 4
    private const val ENTRIES_PER_LONG = Long.SIZE_BITS / PALETTE_BITS
}
