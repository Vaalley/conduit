package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path

/** What to merge, and in which run directory. */
data class MergePlan(
    /** The live server's run directory, with its server stopped. */
    val targetDir: Path,
    /** The level directory to work in, matching the server's `level-name`. */
    val levelName: String = "world",
    /**
     * Empty space to leave around the relocated landmass, in **nether** blocks.
     * The overworld is given eight times as much; see [MergeGeometry.clearanceIn].
     */
    val clearance: Int = WorldMerge.DEFAULT_CLEARANCE,
    /**
     * An offset to check instead of searching for one, so that a rehearsal and
     * the real run place the landmass identically. It is put through exactly the
     * test a searched offset passes rather than being taken on trust.
     */
    val offset: MergeOffset? = null,
    /** How many [MergeGeometry.OFFSET_ALIGNMENT]-block steps out from the origin the search will look. */
    val searchLimit: Int = WorldMerge.DEFAULT_SEARCH_LIMIT,
    /**
     * Stop after choosing the offset and write nothing at all, so that the
     * operator can see the placement and reject one they do not like before the
     * merge touches the save (merge spec, User Story 5).
     */
    val planOnly: Boolean = false,
    /**
     * Go ahead even though Regions, players or Embassy destinations are still
     * anchored in Secondary's End, which this merge destroys. Off by default:
     * the loss is other people's builds and the only irreversible thing in the
     * merge, so it is something the operator says rather than something that
     * happens to them (merge spec, "The End"). See [MergeEnd].
     */
    val acceptEndLoss: Boolean = false,

    /**
     * How many relocated chunks of each dimension are compared block for block
     * against the chunks they came from before the merge commits. The operator's
     * to choose, because it is rehearsal time traded against confidence (merge
     * spec, User Story 19); see [SampledDiff].
     */
    val sample: Int = WorldMerge.DEFAULT_SAMPLE,

    /**
     * How much of Secondary comes across at all: the world border it ran, and how
     * far past that border terrain is still carried so the ground does not end at
     * a visible wall (merge spec, "What comes across"). Both halves are the
     * operator's to state and both are echoed in the plan, so a rehearsal and the
     * real run can be compared. See [SecondaryBorder].
     */
    val border: SecondaryBorder = SecondaryBorder(),
) {
    init {
        require(clearance >= 0) { "the clearance cannot be negative, got $clearance" }
        require(sample >= 0) { "the sample size cannot be negative, got $sample" }
        require(searchLimit in 1..WorldMerge.MAX_SEARCH_LIMIT) {
            "the search limit must be between 1 and ${WorldMerge.MAX_SEARCH_LIMIT} steps of " +
                "${MergeGeometry.OFFSET_ALIGNMENT} blocks, got $searchLimit"
        }
    }
}

/** Where one relocated dimension ends up, and how much room it got. */
data class DimensionPlacement(
    val role: DimensionRole,
    /** Where Secondary's chunk data is now. */
    val secondary: RegionFileArea,
    /** Where it would be after the merge. */
    val lands: RegionFileArea,
    /** How far Primary has been generated here, or null if it never has been. */
    val primary: RegionFileArea?,
    /** Blocks between the landed footprint and Primary's nearest chunk data, or null if there is none. */
    val clearanceAchieved: Int?,
) {
    fun lines(): List<String> = listOf(
        role.id,
        reportLine(
            "  Secondary's footprint",
            "${secondary.describeBlocks()}  (${secondary.fileCount} region " +
                "file${if (secondary.fileCount == 1) "" else "s"})",
        ),
        reportLine("  lands at", lands.describeBlocks()),
        reportLine(
            "  clearance achieved",
            clearanceAchieved?.let { "$it blocks" } ?: "unbounded — Primary has no chunk data here",
        ),
        reportLine("  Primary has reached", primary?.describeBlocks() ?: "nothing at all"),
    )
}

/**
 * Where Secondary is going, for the operator to accept or reject before
 * anything is written (merge spec, User Story 5).
 */
data class MergePlacement(
    val offset: MergeOffset,
    /** True when the operator named the offset and the merge only checked it. */
    val supplied: Boolean,
    /** How many lattice slots the search looked at to get here. */
    val slotsConsidered: Int,
    /** The clearance asked for, in nether blocks. */
    val clearanceRequested: Int,
    val dimensions: List<DimensionPlacement>,
) {
    fun dimension(role: DimensionRole): DimensionPlacement = dimensions.first { it.role == role }

    fun lines(): List<String> = listOf(
        reportLine(
            "offset",
            "${offset.describe(DimensionRole.OVERWORLD)}  " +
                "(nether ${offset.describe(DimensionRole.NETHER)})",
        ),
        reportLine(
            "offset came from",
            if (supplied) {
                "--offset, checked rather than trusted"
            } else {
                "the search — the nearest clear slot, $slotsConsidered tried"
            },
        ),
        reportLine(
            "clearance asked for",
            "$clearanceRequested nether blocks, " +
                "${MergeGeometry.clearanceIn(DimensionRole.OVERWORLD, clearanceRequested)} in the overworld",
        ),
    ) + dimensions.flatMap { it.lines() }
}

private const val REPORT_KEY_WIDTH = 24

/** One `key : value` line of the merge report, aligned with every other one. */
internal fun reportLine(key: String, value: String): String = key.padEnd(REPORT_KEY_WIDTH) + " : " + value

/**
 * Where on Primary's map Secondary can be put down.
 *
 * A slot is a point on the [MergeGeometry.OFFSET_ALIGNMENT] lattice, and it is a
 * candidate when Secondary's footprint *plus its clearance ring* would land on
 * no Primary chunk data — in the overworld and in the nether both, since the
 * offset moves them together and either can rule a slot out on its own. Slots
 * are tried in ascending distance from the origin, so the nearest viable
 * placement wins and the relocated landmass ends up as close to the rest of the
 * map as the operator's clearance allows.
 *
 * An offset the operator supplies goes through [checked], which is the same test
 * the search applies — an offset is never trusted just because someone typed it.
 */
class PlacementSearch(
    private val secondary: Map<DimensionRole, Footprint>,
    private val primary: Map<DimensionRole, Footprint>,
    /** Nether blocks, as the operator states it. */
    private val clearance: Int,
) {

    private data class Clash(val role: DimensionRole, val file: RegionFilePos)

    /** The nearest slot within [limit] steps that clears the requested clearance. */
    fun nearestSlot(limit: Int): MergePlacement {
        var considered = 0
        for (offset in slots(limit)) {
            considered++
            // One clash is all it takes; the search never needs the rest of them.
            if (clashes(offset, limit = 1).isEmpty()) {
                return placement(offset, supplied = false, slotsConsidered = considered)
            }
        }
        throw MigrationRefused(
            "no ${MergeGeometry.OFFSET_ALIGNMENT}-aligned slot within " +
                "${limit * MergeGeometry.OFFSET_ALIGNMENT} blocks of the origin clears $clearance " +
                "nether blocks of Primary's chunk data — $considered slots tried, and " +
                MergeGeometry.RELOCATED_ROLES.joinToString("; ", transform = ::reach) +
                ". Ask for less clearance, or search further out",
        )
    }

    /** [offset] put through the test a searched slot has to pass. */
    fun checked(offset: MergeOffset): MergePlacement {
        if (offset.x == 0 && offset.z == 0) {
            throw MigrationRefused(
                "an offset of ${offset.describe(DimensionRole.OVERWORLD)} would leave Secondary " +
                    "exactly where it is — the landmass has to move",
            )
        }
        val clashes = clashes(offset, limit = CLASHES_NAMED + 1)
        if (clashes.isEmpty()) return placement(offset, supplied = true, slotsConsidered = 1)
        throw MigrationRefused(refusal(offset, clashes))
    }

    /**
     * The lattice, nearest first. Ties are broken towards +X and then +Z so the
     * answer is the same on the rehearsal and on the night, and so a printed
     * offset reads as a move east rather than an arbitrary one. The origin
     * itself is not a slot: it would leave Secondary where it is.
     */
    private fun slots(limit: Int): Sequence<MergeOffset> =
        (-limit..limit).flatMap { i -> (-limit..limit).map { j -> i to j } }
            .filter { (i, j) -> i != 0 || j != 0 }
            .sortedWith(
                compareBy(
                    { (i, j) -> i.toLong() * i + j.toLong() * j },
                    { (i, _) -> -i },
                    { (_, j) -> -j },
                ),
            )
            .asSequence()
            .map { (i, j) ->
                MergeOffset(i * MergeGeometry.OFFSET_ALIGNMENT, j * MergeGeometry.OFFSET_ALIGNMENT)
            }

    /** Primary region files [offset] would land on or crowd, at most [limit] of them. */
    private fun clashes(offset: MergeOffset, limit: Int): List<Clash> {
        val found = mutableListOf<Clash>()
        for (role in MergeGeometry.RELOCATED_ROLES) {
            if (found.size >= limit) break
            val ring = ringed(role, offset) ?: continue
            val occupied = primary[role] ?: continue
            found += occupied.within(ring, limit - found.size).map { Clash(role, it) }
        }
        return found
    }

    /** Where [role]'s footprint lands under [offset], with its clearance ring around it. */
    private fun ringed(role: DimensionRole, offset: MergeOffset): RegionFileArea? =
        secondary[role]?.bounds?.movedBy(offset, role)?.grownBy(ringFiles(role))

    /** The ring in whole region files, rounded up so the operator always gets at least what they asked for. */
    private fun ringFiles(role: DimensionRole): Int {
        val blocks = MergeGeometry.clearanceIn(role, clearance)
        return (blocks + MergeGeometry.REGION_FILE_BLOCKS - 1) / MergeGeometry.REGION_FILE_BLOCKS
    }

    private fun placement(offset: MergeOffset, supplied: Boolean, slotsConsidered: Int) = MergePlacement(
        offset = offset,
        supplied = supplied,
        slotsConsidered = slotsConsidered,
        clearanceRequested = clearance,
        dimensions = MergeGeometry.RELOCATED_ROLES.mapNotNull { role ->
            val source = secondary[role]?.bounds ?: return@mapNotNull null
            val lands = source.movedBy(offset, role)
            DimensionPlacement(
                role = role,
                secondary = source,
                lands = lands,
                primary = primary[role]?.bounds,
                clearanceAchieved = primary[role]?.clearanceFrom(lands),
            )
        },
    )

    /**
     * The refusal, named after the files it refused over. Only the first
     * dimension to clash is described: an operator fixes one offset at a time,
     * and a second dimension's list would not change what they do next.
     */
    private fun refusal(offset: MergeOffset, clashes: List<Clash>): String {
        val role = clashes.first().role
        val here = clashes.filter { it.role == role }
        val named = here.take(CLASHES_NAMED).map { it.file.fileName }
        val listed = if (named.size == 1) {
            named.single()
        } else {
            named.dropLast(1).joinToString(", ") + " and " + named.last()
        }
        return "the offset ${offset.describe(DimensionRole.OVERWORLD)} does not clear Primary's chunk " +
            "data: Secondary's ${role.id} would come within " +
            "${MergeGeometry.clearanceIn(role, clearance)} blocks of Primary's $listed" +
            (if (here.size > named.size) " (and more)" else "") +
            " — choose another offset, or ask for less clearance"
    }

    private fun reach(role: DimensionRole): String = primary[role]?.bounds
        ?.let { "Primary's ${role.id} reaches ${it.describeBlocks()}" }
        ?: "Primary's ${role.id} has no chunk data"

    private companion object {
        /** How many clashing region files a refusal spells out before it stops listing them. */
        const val CLASHES_NAMED = 4
    }
}

/**
 * The merge of Secondary into Primary (merge spec, User Stories 1–7): the third
 * offline tool of the cutover, after `migrate` and `importNucleus`, run against
 * the live production save in a downtime window.
 *
 * It measures how far Primary has been generated and how much ground Secondary
 * covers, finds or checks the offset Secondary will move by, and then hands the
 * placement to [MergeStaging], which does all the writing: the chunk relocation
 * and then every sweep that rewrites what recorded a place in Secondary. The
 * [MergeReport] it hands back states the placement first, because that is the
 * thing the operator has to accept or reject.
 *
 * With [MergePlan.planOnly] it stops at the placement and **writes nothing** —
 * not a staging directory, not a marker, not a byte — so asking where Secondary
 * would go costs nothing and can be asked as often as the operator likes, and a
 * placement can be rejected before the save is touched (merge spec, User
 * Story 5).
 *
 * The refusals it already makes are the ones that must happen before any later
 * phase can start writing: a run directory that is not the live server's, a save
 * that has already been merged, and a staging directory left behind by a run
 * that died. They are here rather than with the work they guard because a
 * refusal is only worth anything if it happens first.
 *
 * Like [PortalImport] and [EmbassyImport], the merge is all-or-nothing when it
 * does start writing: everything is read, converted and checked before a byte is
 * written, output is built under [STAGING_DIRECTORY] inside the target, and only
 * a complete merge is moved into place.
 */
class WorldMerge(private val plan: MergePlan) {

    private val staging: Path = plan.targetDir.resolve(STAGING_DIRECTORY)
    private val levelDir: Path = plan.targetDir.resolve(plan.levelName)

    fun run(): MergeReport {
        refuseUnlessLiveRunDirectory()
        refuseIfAlreadyMerged()

        val measured = footprints(WorldLayout.SECONDARY)
        refuseIfSecondaryIsMissing(measured)

        // The clip comes before the search rather than before the relocation,
        // because what it takes out is measured ground: a chunk left outside the
        // border must not be in the footprint the slot is sized from, or the
        // search would reserve room in Primary for terrain that never arrives.
        val secondary = measured.mapValues { (_, footprint) -> footprint.clippedTo(plan.border) }
        val clip = clipReport(measured)
        refuseIfTheBorderLeavesNothing(secondary)

        val search = PlacementSearch(secondary, footprints(WorldLayout.PRIMARY), plan.clearance)
        // Whichever way the offset is arrived at, the merge writes the one it
        // used into the save's own marker and everything downstream — the claim
        // path most of all — reads it back from there. So a rehearsal and the
        // real run agree because the operator passed --offset, and the claim path
        // agrees with the night because it never held a second copy at all.
        val placement = plan.offset?.let(search::checked) ?: search.nearestSlot(plan.searchLimit)
        if (plan.planOnly) return MergeReport(placement, listOf(clip))
        // The clip is not a phase of the staging — it was decided up here, and it
        // constrained every phase down there — so it leads the sections rather
        // than being staged among them.
        val written = MergeStaging(plan, staging, levelDir).write(placement)
        return written.copy(sections = listOf(clip) + written.sections)
    }

    // ---- refusals -----------------------------------------------------------

    /**
     * The target has to be the run directory the server actually plays on —
     * both Worlds' chunk data are in one save by now, and the Regions and player
     * records the later phases sweep are beside it. Nothing is created on the
     * operator's behalf: a missing `regions.json` means the wrong path, and that
     * is a decision, not a default.
     */
    private fun refuseUnlessLiveRunDirectory() {
        if (!Files.isDirectory(plan.targetDir)) {
            throw MigrationRefused("${plan.targetDir} is not a directory")
        }
        for (artifact in listOf(plan.levelName, MOD_DIRECTORY, REGIONS_FILE)) {
            if (Files.notExists(plan.targetDir.resolve(artifact))) {
                throw MigrationRefused(
                    "${plan.targetDir} has no \"$artifact\" — merge the live server's own run " +
                        "directory (see docs/migration.md), not a fresh one",
                )
            }
        }
    }

    /**
     * A merged save carries a stamp, and a merge is not something to do twice:
     * the second run would relocate an already-relocated landmass. A staging
     * directory is refused rather than reused and deliberately left where it is,
     * because it is the only evidence of what the run that died had built.
     */
    private fun refuseIfAlreadyMerged() {
        val marker = plan.targetDir.resolve(MARKER_FILE)
        if (Files.exists(marker)) {
            throw MigrationRefused(
                "${plan.targetDir} has already been merged: ${Files.readString(marker)}",
            )
        }
        if (Files.exists(staging)) {
            throw MigrationRefused(
                "$staging is left over from an interrupted merge; " +
                    "look at what it holds, then remove it and run again",
            )
        }
    }

    /**
     * No Secondary chunk data at all means the wrong save, not an empty one:
     * a placement search against nothing would happily answer, and the answer
     * would be meaningless.
     */
    private fun refuseIfSecondaryIsMissing(secondary: Map<DimensionRole, Footprint>) {
        if (secondary.values.any { !it.isEmpty }) return
        val folders = MergeGeometry.RELOCATED_ROLES.map {
            Footprint.storageFolder(levelDir, WorldLayout.SECONDARY.dimension(it))
        }
        throw MigrationRefused(
            "no Secondary chunk data under ${folders.joinToString(" or ")} — " +
                "is ${plan.targetDir} the run directory the Portal migration produced?",
        )
    }

    /**
     * A border that carries none of Secondary is a mistyped option rather than a
     * map with nothing on it, and relocating nothing while reporting success is
     * the worst answer available. Named after the numbers that produced it, so
     * the operator can see which of the two they got wrong (merge spec, User
     * Story 49).
     */
    private fun refuseIfTheBorderLeavesNothing(clipped: Map<DimensionRole, Footprint>) {
        if (clipped.values.any { !it.isEmpty }) return
        throw MigrationRefused(
            "Secondary's border of ${plan.border.describe()} carries none of Secondary's chunk data: " +
                "every region file it has lies outside ${plan.border.files.describeBlocks()}. " +
                "Check --border and --bleed against the border Secondary actually ran",
        )
    }

    // ---- measuring ----------------------------------------------------------

    private fun footprints(world: WorldTrio): Map<DimensionRole, Footprint> =
        MergeGeometry.RELOCATED_ROLES.associateWith { Footprint.of(levelDir, world.dimension(it)) }

    /**
     * What the clip took out of [measured], for the report — the region files of
     * Secondary that are not coming across, by the dimension each is in.
     *
     * Derived from the unclipped measurement rather than counted as the clip is
     * applied, so the two can never disagree about what was left behind.
     */
    private fun clipReport(measured: Map<DimensionRole, Footprint>) = BorderClipReport(
        border = plan.border,
        leftOutside = measured
            .mapValues { (_, footprint) -> footprint.files.filterNot(plan.border::keeps) }
            .filterValues { it.isNotEmpty() },
    )

    companion object {
        /**
         * Nether blocks of empty ground to leave around the landmass unless the
         * operator says otherwise — one lattice step's worth in the overworld.
         * It is a starting point for a judgement, not a recommendation: the
         * distance it produces should be looked at against the real map before
         * the real run.
         */
        const val DEFAULT_CLEARANCE = 512

        /** Lattice steps the search looks out to by default: 64 × 4096 blocks, well inside the world border. */
        const val DEFAULT_SEARCH_LIMIT = 64

        /**
         * Chunks of each relocated dimension compared against their source
         * unless the operator says otherwise. Enough that a relocation which
         * lost a region file cannot hide behind the ones that landed, cheap
         * enough that nobody is tempted to turn it off to save a minute.
         */
        const val DEFAULT_SAMPLE = 64

        /**
         * Blocks from the origin to Secondary's world border on each horizontal
         * axis. The operator's own number, from the server that ran it, and an
         * option rather than a constant because a rehearsal against a copy of
         * production has to be able to state it and be believed.
         */
        const val DEFAULT_BORDER = 50_000

        /**
         * Blocks of terrain carried past the border, so a player standing at it
         * sees ground continuing rather than the edge of the import. One region
         * file wide, which is what [MergeGeometry.OFFSET_ALIGNMENT] moves whole in
         * both dimensions — so the bleed costs the clip nothing it was not
         * already working in.
         */
        const val DEFAULT_BLEED = MergeGeometry.REGION_FILE_BLOCKS

        /** As far as the search will ever be asked to look, so a slip cannot ask for millions of slots. */
        const val MAX_SEARCH_LIMIT = 256

        const val STAGING_DIRECTORY = ".mctraveler-merge"
        const val MOD_DIRECTORY = "mctraveler"

        /** The stamp a finished merge leaves on the save, and the thing a second run refuses over. */
        const val MARKER_FILE = "$MOD_DIRECTORY/merge.json"
        const val REGIONS_FILE = "regions.json"
    }
}
