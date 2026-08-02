package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * One phase of the merge's account of itself.
 *
 * The merge is a sequence of phases — the chunks move, then each sweep rewrites
 * what recorded a place in Secondary — and each one answers for its own counts
 * and no one else's. A phase therefore contributes a section rather than fields
 * on a shared report, which is what lets a later phase be added without the
 * phases before it, or the report they hand back, being touched at all.
 */
interface MergeSection {
    /** This phase's lines, aligned with every other section's by [reportLine]. */
    fun lines(): List<String>
}

/**
 * What the merge did, for the operator to check against what they expected
 * (merge spec, User Story 48).
 *
 * The placement always leads, whether or not anything was written: where
 * Secondary went is the thing the operator has to accept or reject, and a plan
 * and a completed merge open with exactly the same section so that a rehearsal
 * reads as the beginning of the real run. Everything the phases did follows it,
 * in the order they ran.
 *
 * A plan carries no sections at all, so asking one for a phase's counts is a
 * question about work that never happened and [section] says so rather than
 * inventing a zero.
 */
data class MergeReport(
    val placement: MergePlacement,
    /** One section per phase that ran, in phase order. Empty when the merge only planned. */
    val sections: List<MergeSection> = emptyList(),
) {
    val offset: MergeOffset get() = placement.offset

    val relocation: RelocationReport get() = section()
    val regions: MergeRegionsReport get() = section()
    val players: PlayerSweepReport get() = section()

    fun lines(): List<String> = placement.lines() + sections.flatMap(MergeSection::lines)

    /** The section [T] answers for, or a complaint naming the phases that did run. */
    inline fun <reified T : MergeSection> section(): T =
        sections.filterIsInstance<T>().firstOrNull() ?: throw IllegalStateException(
            "no ${T::class.simpleName} in this merge report — it holds " +
                sections.joinToString { it::class.simpleName.orEmpty() }
                    .ifEmpty { "nothing but the placement, so the merge was a plan" },
        )
}

/**
 * The writing half of the merge, and the discipline that makes it safe to
 * abandon (merge spec, "Staging"; User Story 45).
 *
 * Everything is built inside [WorldMerge.STAGING_DIRECTORY] and nothing outside
 * it is touched until all of it has succeeded. The staging directory sits inside
 * the target so the commit is a rename on the same filesystem rather than a copy
 * that can half-finish, which is the same shape [PortalImport] and
 * [EmbassyImport] use and for the same reason.
 *
 * A run that *fails* clears its staging directory, so an operator who fixes the
 * cause can run again immediately. A run that is *interrupted* — killed, or the
 * machine lost — leaves one behind by definition, and [WorldMerge] refuses to
 * start while it is there rather than reusing it: it is the only evidence of
 * what the dead run had built (merge spec, User Story 50).
 *
 * **The phases stage; only this commits.** Every phase writes its output through
 * [adding] or [replacing] and none of them moves a byte into the live save, so
 * the whole merge is one all-or-nothing move: a player sweep that fails cannot
 * leave a rewritten `regions.json` sitting beside chunks that never arrived. The
 * order the phases run in is the order they are called in [write], and a new one
 * is a line added there.
 *
 * The staging tree is a mirror of the run directory: a file staged for
 * `<target>/regions.json` is built at `<staging>/regions.json`, and committing is
 * walking that mirror and moving each file onto its twin. The corollary is the
 * guarantee the sweeps rest on — a file that is never staged is never touched,
 * so a player who was already in Primary comes out byte-for-byte as they went in
 * rather than rewritten to an identical value.
 */
class MergeStaging(
    private val plan: MergePlan,
    private val staging: Path,
    /** The live save the phases read from; the staged twin of it is built under [staging]. */
    private val levelDir: Path,
    private val tool: McaSelector = McaSelector.resolved(),
) {

    private val stagedLevelDir: Path = staging.resolve(plan.levelName)

    /**
     * Destinations a phase has staged a *replacement* for, so the commit knows
     * which of them it is entitled to land on top of. See [commit].
     */
    private val replaced = mutableSetOf<Path>()

    fun write(placement: MergePlacement): MergeReport {
        Files.createDirectories(staging)
        val report = try {
            // The merge in the order it happens. Each phase stages its own output
            // and returns the section it answers for; nothing reaches the save
            // until commit() below, so a phase failing here costs only the work.
            val relocation = ChunkRelocation(
                levelDir = levelDir,
                stagedLevelDir = stagedLevelDir,
                workDir = staging.resolve(WORK_DIRECTORY),
                offset = placement.offset,
                tool = tool,
            ).run()
            val regions = MergeRegions(plan.targetDir, this, placement.offset).sweep()
            val players = PlayerSweep(plan, placement.offset).sweep(this)
            stampAsMerged(placement)
            MergeReport(placement, listOf(relocation, regions, players))
        } catch (failure: Throwable) {
            // The merge only ever copies — `--worlds move` is deliberately not
            // offered — so nothing here is the last copy of anything, and the
            // staging directory can go without taking evidence with it.
            deleteRecursively(staging)
            throw failure
        }
        commit()
        return report
    }

    // ---- what a phase stages ------------------------------------------------

    /**
     * Where a file the save does not have yet is built. Its destination must
     * still be free when the merge commits; see [commit].
     */
    fun adding(destination: Path): Path = prepared(destination)

    /**
     * Where [live] — a file the save already has — is rebuilt before the merge
     * replaces it. Staging a live file is the decision to overwrite it, and it is
     * the only thing that gives the commit leave to do so.
     */
    fun replacing(live: Path): Path {
        // `add`, not `+=`: a Path is itself an Iterable<Path>, so the operator
        // would add its name elements one by one rather than the path.
        replaced.add(live.normalize())
        return prepared(live)
    }

    private fun prepared(live: Path): Path {
        val staged = staging.resolve(plan.targetDir.relativize(live).toString())
        Files.createDirectories(staged.parent)
        return staged
    }

    /**
     * The stamp a finished merge leaves on the save.
     *
     * [WorldMerge.MARKER_FILE] is what a second run refuses over, so this is the
     * thing that makes a merge unrepeatable (merge spec, User Story 46). It
     * records the offset as well as the time because months later the question
     * asked of a save is not only whether it was merged but by how far — a
     * coordinate that looks wrong is diagnosable from this file alone.
     */
    private fun stampAsMerged(placement: MergePlacement) {
        Files.writeString(
            adding(plan.targetDir.resolve(WorldMerge.MARKER_FILE)),
            "{\"mergedAt\":\"${Instant.now()}\"," +
                "\"offsetX\":${placement.offset.x},\"offsetZ\":${placement.offset.z}}\n",
        )
    }

    // ---- the one commit -----------------------------------------------------

    /**
     * The staged merge moved into the live save, in one pass, after every
     * destination has been checked and before any of them has been touched — so
     * a save that changed underneath the merge leaves it untouched rather than
     * half-merged.
     *
     * What "checked" means depends on how the file was staged, and the two cases
     * are opposite claims about the same save:
     *
     * - An **addition** must not exist. Relocated chunk data cannot collide — the
     *   placement search proved every destination region file free and the offset
     *   moves whole files — so a file where one should not be means the save is
     *   not the one that was planned against, and stopping is the only safe
     *   answer to that.
     * - A **replacement** must exist. `regions.json` and a player's records were
     *   read off disk by the phase that rewrote them, and one that has since gone
     *   is the same evidence read the other way round.
     */
    private fun commit() {
        val moves = staged()
        for ((_, destination) in moves) refuseIfTheSaveMoved(destination)
        for ((file, destination) in moves) {
            Files.createDirectories(destination.parent)
            if (destination in replaced) {
                Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(file, destination)
            }
        }
        deleteRecursively(staging)
    }

    private fun refuseIfTheSaveMoved(destination: Path) {
        val expected = destination in replaced
        if (Files.exists(destination) == expected) return
        throw IllegalStateException(
            if (expected) {
                "$destination was read by the merge and is no longer there, so the merge stopped " +
                    "rather than write a file it can no longer be sure of. "
            } else {
                "$destination already exists, so the merge stopped rather than overwrite it. "
            } +
                "Nothing has been changed in ${plan.targetDir}, and $staging still holds " +
                "everything the merge built.",
        )
    }

    /**
     * Every staged file, paired with where in the run directory it belongs. The
     * tool's own scratch space is not staged output and is the one thing under
     * the staging directory that is never committed.
     */
    private fun staged(): List<Pair<Path, Path>> {
        val work = staging.resolve(WORK_DIRECTORY)
        return Files.walk(staging).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { !it.startsWith(work) }
                .map { it to plan.targetDir.resolve(staging.relativize(it).toString()).normalize() }
                .toList()
        }
    }

    private fun deleteRecursively(directory: Path) {
        if (Files.notExists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        /** The tool's own scratch files, inside staging so they are never committed. */
        const val WORK_DIRECTORY = "mcaselector"
    }
}
