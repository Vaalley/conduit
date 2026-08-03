package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** What the merge moved in one of Secondary's dimensions. */
data class DimensionRelocation(
    val role: DimensionRole,
    /** Chunks that arrived in Primary, counted in the staged files rather than claimed. */
    val relocated: Int,
    /** Chunks left behind because vanilla had not finished generating them. */
    val dropped: Int,
    val files: Int,
    val bytes: Long,
    /**
     * Chunks left behind because they sit outside Secondary's world border and
     * the bleed past it (ticket 13). Nothing to do with [dropped]: these are
     * chunks vanilla finished, in region files the clip does not carry, and the
     * two counts never overlap.
     */
    val outsideBorder: Int = 0,
) {
    fun lines(): List<String> = listOf(
        role.id,
        reportLine("  chunks relocated", "$relocated"),
        reportLine("  chunks dropped", "$dropped (not fully generated)"),
    ) + outsideBorderLine() + listOf(
        reportLine("  files written", "$files"),
        reportLine("  bytes transferred", "$bytes"),
    )

    /**
     * Said only when there were any, because on a Secondary that never generated
     * anything past its border there is nothing here to report — and a merge that
     * printed a zero would invite the operator to wonder what it had thrown away.
     * [BorderClipReport] states the border itself either way.
     */
    private fun outsideBorderLine(): List<String> = if (outsideBorder == 0) {
        emptyList()
    } else {
        listOf(reportLine("  chunks outside border", "$outsideBorder (past Secondary's world border)"))
    }
}

/** Something of Secondary's the merge deliberately did not carry across. */
data class Discarded(val what: String, val files: Int, val bytes: Long)

/** What the chunk relocation did, for the operator to check against what they expected. */
data class RelocationReport(
    val dimensions: List<DimensionRelocation>,
    val discarded: List<Discarded>,
) : MergeSection {
    val relocated: Int get() = dimensions.sumOf { it.relocated }
    val dropped: Int get() = dimensions.sumOf { it.dropped }
    val bytes: Long get() = dimensions.sumOf { it.bytes }

    /** Chunks the border clip left in Secondary, across every relocated dimension (ticket 13). */
    val outsideBorder: Int get() = dimensions.sumOf { it.outsideBorder }

    fun dimension(role: DimensionRole): DimensionRelocation = dimensions.first { it.role == role }

    companion object {
        /**
         * The section a run contributes when it reused chunks a previous run had
         * already relocated (see [MergePlan.reuseRelocation]).
         *
         * It reports nothing rather than the previous run's counts, because those
         * counts belong to that run and repeating them here would let a resumed
         * merge look like it had done work it did not do. What this run did is
         * exactly one thing — decline to do it again — and the report says so.
         */
        fun reused() = RelocationReport(dimensions = emptyList(), discarded = emptyList())
    }

    override fun lines(): List<String> = if (dimensions.isEmpty()) {
        listOf(
            reportLine(
                "chunks moved across",
                "none by this run — it reused the staging area a previous run relocated, which " +
                    "the sampled diff had already compared against its sources",
            ),
        )
    } else {
        dimensions.flatMap { it.lines() } +
            reportLine("relocated in total", "$relocated chunks, $dropped dropped, $bytes bytes") +
            discarded.map {
                reportLine("discarded", "${it.what} — ${it.files} file${if (it.files == 1) "" else "s"}")
            }
    }
}

/**
 * Secondary's chunk data, moved into Primary's dimensions at the planned offset
 * (merge spec, "Relocation"; ticket 02).
 *
 * The move itself is [McaSelector]'s: this decides *what* moves and *where to*,
 * proves afterwards that what arrived is what was meant to, and never lets any
 * of it out of the staging area. Two passes of the tool per dimension:
 *
 * 1. **Select** the chunks vanilla has finished generating. Everything else is
 *    frontier — half-generated chunks that would otherwise land inside Primary
 *    as terrain from another seed, so they are never written anywhere at all
 *    (merge spec, User Story 14).
 * 2. **Relocate** exactly that selection into a staged copy of Primary's own
 *    dimension folder, terrain, entities and points of interest together. A
 *    village whose beds moved but whose point-of-interest records did not would
 *    be a broken trading hall, so all three folders always travel as one.
 *
 * Because [MergeGeometry.OFFSET_ALIGNMENT] makes the offset a whole number of
 * region files in both dimensions, every source file lands on exactly one
 * destination file and the destination files are known before the tool runs.
 * That is what lets this stage into an *empty* directory rather than a copy of
 * Primary: Primary's own region files are never opened, never rewritten, and
 * never at risk, and the commit is a move of files that did not previously exist.
 *
 * Secondary's End is not here at all. It is discarded rather than relocated
 * (merge spec, "The End"), as is Secondary's level-wide saved data, which was
 * never imported at the Portal cutover either — both are counted and named in
 * the report so the loss is stated rather than silent.
 *
 * [SecondaryBorder] narrows the first of those two passes. The selection the tool
 * produces is filtered down to the region files inside Secondary's world border
 * plus its bleed before it is handed back to the tool to import, so a chunk out
 * beyond the border is never written anywhere — the same shape as the frontier
 * drop above, and for the same reason.
 */
class ChunkRelocation(
    private val levelDir: Path,
    /** Primary's dimensions as they are being rebuilt inside the staging area. */
    private val stagedLevelDir: Path,
    /** Scratch space for the tool's own files, inside staging and never committed. */
    private val workDir: Path,
    private val offset: MergeOffset,
    /** How much of Secondary comes across at all; see [SecondaryBorder] and ticket 13. */
    private val border: SecondaryBorder,
    private val tool: McaSelector,
) {

    fun run(): RelocationReport = RelocationReport(
        dimensions = MergeGeometry.RELOCATED_ROLES.mapNotNull(::relocate),
        discarded = discarded(),
    )

    /**
     * One dimension moved, or null when Secondary never generated it — a World
     * whose nether nobody ever entered is a legitimate save, not a broken one.
     */
    private fun relocate(role: DimensionRole): DimensionRelocation? {
        val from = Footprint.storageFolder(levelDir, WorldLayout.SECONDARY.dimension(role))
        val terrain = from.resolve(TERRAIN)
        val sourceFiles = regionFilesIn(terrain).mapNotNull { RegionFilePos.parse(it.name) }
        if (sourceFiles.isEmpty()) return null

        // Everything the border keeps out is counted off disk, the same way every
        // other count here is, rather than inferred from what the tool said.
        val (carried, outside) = sourceFiles.partition(border::keeps)
        val outsideBorder = outside.sumOf { chunksIn(terrain.resolve(it.fileName)) }

        val into = Footprint.storageFolder(stagedLevelDir, WorldLayout.PRIMARY.dimension(role))
        prepareDestination(into, carried, role)

        Files.createDirectories(workDir)
        val selection = workDir.resolve("finished-${role.id}.csv")
        tool.select(from, selection)
        val inside = workDir.resolve("inside-the-border-${role.id}.csv")
        val selected = clipToTheBorder(selection, inside, carried.toSet())

        // Nothing survived the clip in this dimension, so there is no import to
        // ask for — and asking anyway would be asking the tool to prove a
        // negative it answers "no files" to either way.
        val output = if (selected == 0) {
            ""
        } else {
            tool.relocate(
                from = from,
                into = into,
                selection = inside,
                chunksX = offset.shiftX(role) / BLOCKS_PER_CHUNK,
                chunksZ = offset.shiftZ(role) / BLOCKS_PER_CHUNK,
            )
        }

        dropEmptyFiles(into)
        val relocated = chunksUnder(into.resolve(TERRAIN))
        // MCA Selector reports success having done nothing when it finds no region
        // files to write into, so what arrived is counted rather than assumed.
        if (relocated != selected) {
            throw IllegalStateException(
                "the relocation of Secondary's ${role.id} put $relocated chunks into $into, but " +
                    "$selected were selected to move. Nothing has been moved into place.\n" +
                    output.prependIndent("  "),
            )
        }
        val staged = CHUNK_DIRECTORIES.flatMap { regionFilesIn(into.resolve(it)) }
        return DimensionRelocation(
            role = role,
            relocated = relocated,
            // What is left over once the arrivals and the border's exiles are
            // taken off the source count is exactly the frontier, so "dropped"
            // goes on meaning what it has always meant.
            dropped = chunksUnder(terrain) - relocated - outsideBorder,
            files = staged.size,
            bytes = staged.sumOf(Files::size),
            outsideBorder = outsideBorder,
        )
    }

    /**
     * [selection] with every chunk outside the border struck out, written to
     * [into] and counted (ticket 13).
     *
     * MCA Selector writes one line per chunk as `region x;region z;chunk x;chunk
     * z`, and a two-field line when a whole region file was selected — so the
     * first two fields name the file either way, and are all the clip has to
     * read to decide a line's fate.
     *
     * Filtering the tool's own selection rather than asking it a narrower
     * question is what keeps the clip from being a second opinion about which
     * chunks are finished: the selection that reaches the import is the one the
     * tool produced, minus whole region files. [carried] is a set of region file
     * positions for exactly that reason — the clip's unit is the file, so a line
     * is kept or struck by which file it names and never by the chunk.
     */
    private fun clipToTheBorder(selection: Path, into: Path, carried: Set<RegionFilePos>): Int {
        if (Files.notExists(selection)) return 0
        var chunks = 0
        val kept = mutableListOf<String>()
        for (line in Files.readAllLines(selection)) {
            if (line.isBlank()) continue
            val fields = line.split(';')
            require(line != "inverted") {
                "MCA Selector wrote an inverted selection to $selection, which the merge cannot count"
            }
            val file = RegionFilePos(fields[0].trim().toInt(), fields[1].trim().toInt())
            if (file !in carried) continue
            kept += line
            chunks += if (fields.size == 2) CHUNKS_PER_REGION_FILE else 1
        }
        Files.write(into, kept)
        return chunks
    }

    /**
     * The destination folders, and an empty region file for every file that will
     * land in one.
     *
     * Both halves are load-bearing. MCA Selector merges into the *target* world,
     * and a target holding no region files at all is "no files" to it — it
     * returns successfully having moved nothing. Creating the files it is about
     * to fill is therefore not a convenience but the thing that makes the
     * relocation happen, and it is only possible to know them in advance because
     * the offset moves whole region files.
     *
     * Only the files that are actually coming across get one. A destination
     * prepared for a source file the border keeps out would be an empty region
     * file staged for no reason, and [dropEmptyFiles] would have to clean it up
     * again to stop it reaching Primary.
     */
    private fun prepareDestination(into: Path, sourceFiles: List<RegionFilePos>, role: DimensionRole) {
        val destinations = sourceFiles
            .map { RegionFilePos(it.x + offset.regionFileShiftX(role), it.z + offset.regionFileShiftZ(role)) }
        for (folder in CHUNK_DIRECTORIES) {
            val directory = into.resolve(folder)
            Files.createDirectories(directory)
            for (destination in destinations) {
                // An empty region file is its header and nothing else: 1024 chunk
                // locations and 1024 timestamps, all zero.
                Files.write(directory.resolve(destination.fileName), ByteArray(HEADER_BYTES))
            }
        }
    }

    /**
     * Destination files nothing landed in, removed before they can be committed.
     *
     * A source region file at Secondary's frontier can hold nothing but
     * unfinished chunks, and its destination then stays as empty as we created
     * it. Committing that would put a region file into Primary holding no chunks
     * — litter that every later footprint measurement would have to treat as
     * chunk data (see [Footprint], which counts files rather than chunks).
     */
    private fun dropEmptyFiles(into: Path) {
        for (folder in CHUNK_DIRECTORIES) {
            regionFilesIn(into.resolve(folder)).filter { chunksIn(it) == 0 }.forEach(Files::delete)
        }
    }

    /** Secondary's End and its saved data, measured so the report can state what was left behind. */
    private fun discarded(): List<Discarded> = buildList {
        measure(
            "Secondary's End",
            listOf(Footprint.storageFolder(levelDir, WorldLayout.SECONDARY.dimension(DimensionRole.END))),
        )?.let(::add)
        measure(
            "Secondary's level-wide saved data",
            MergeGeometry.RELOCATED_ROLES.map {
                Footprint.storageFolder(levelDir, WorldLayout.SECONDARY.dimension(it)).resolve(SAVED_DATA)
            },
        )?.let(::add)
    }

    private fun measure(what: String, trees: List<Path>): Discarded? {
        var files = 0
        var bytes = 0L
        for (tree in trees) {
            if (!Files.isDirectory(tree)) continue
            Files.walk(tree).use { paths ->
                paths.filter(Files::isRegularFile).forEach {
                    files++
                    bytes += Files.size(it)
                }
            }
        }
        return if (files == 0) null else Discarded(what, files, bytes)
    }

    // ---- counting -----------------------------------------------------------

    private fun regionFilesIn(folder: Path): List<Path> {
        if (!Files.isDirectory(folder)) return emptyList()
        return Files.newDirectoryStream(folder, "r.*.mca").use { it.sortedBy(Path::toString) }
    }

    /** Every chunk stored under [folder], across all of its region files. */
    private fun chunksUnder(folder: Path): Int = regionFilesIn(folder).sumOf(::chunksIn)

    /**
     * How many chunks a region file holds, read from its header alone.
     *
     * The first 4096 bytes are 1024 four-byte entries, one per chunk position:
     * a three-byte sector offset and a one-byte length, both zero when nothing
     * is stored there. Counting them needs no chunk decompressed and no NBT
     * parsed, which keeps the merge's own arithmetic independent of whether the
     * data inside is readable — that is the audit's question, not this one.
     */
    private fun chunksIn(file: Path): Int {
        val header = ByteArray(LOCATIONS_BYTES)
        Files.newInputStream(file).use {
            if (it.readNBytes(header, 0, LOCATIONS_BYTES) < LOCATIONS_BYTES) return 0
        }
        var chunks = 0
        for (entry in 0 until CHUNKS_PER_REGION_FILE) {
            val at = entry * LOCATION_BYTES
            // An all-zero entry means nothing is stored at that position; what the
            // sector offset and length say when it is non-zero does not matter here.
            if ((0 until LOCATION_BYTES).any { header[at + it].toInt() != 0 }) chunks++
        }
        return chunks
    }

    private companion object {
        /** The three folders a dimension's chunk data is split across; all three are relocated. */
        val CHUNK_DIRECTORIES = Footprint.CHUNK_DIRECTORIES

        /** The one that holds the blocks, and the one MCA Selector maps a relocation from. */
        const val TERRAIN = "region"

        /** Maps, raids and the rest — never imported at the Portal cutover, and not now. */
        const val SAVED_DATA = "data"

        const val BLOCKS_PER_CHUNK = 16
        const val CHUNKS_PER_REGION_FILE = 1024
        const val LOCATION_BYTES = 4
        const val LOCATIONS_BYTES = CHUNKS_PER_REGION_FILE * LOCATION_BYTES

        /** A region file's header: the chunk locations, then a timestamp for each. */
        const val HEADER_BYTES = LOCATIONS_BYTES * 2
    }
}
