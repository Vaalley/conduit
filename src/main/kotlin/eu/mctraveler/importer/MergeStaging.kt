package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * What the merge did, for the operator to check against what they expected
 * (merge spec, User Story 48).
 *
 * The placement is embedded rather than replaced: the operator has to see where
 * Secondary went whether or not anything was written, so a plan and a merge
 * report the same first section and the merge adds to it.
 */
data class MergeReport(
    val placement: MergePlacement,
    /** Null when the merge was only asked where Secondary would go. */
    val relocation: RelocationReport? = null,
) {
    val offset: MergeOffset get() = placement.offset

    fun lines(): List<String> = placement.lines() + (relocation?.lines() ?: emptyList())
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
 * This is the seam the rest of the merge's writing hangs off. Later phases stage
 * their own output beside the chunk data and commit it here, so that the whole
 * merge stays one all-or-nothing move.
 */
class MergeStaging(
    private val plan: MergePlan,
    private val staging: Path,
    private val levelDir: Path,
    private val tool: McaSelector = McaSelector.resolved(),
) {

    private val stagedLevelDir: Path = staging.resolve(plan.levelName)

    fun write(placement: MergePlacement): MergeReport {
        Files.createDirectories(staging)
        val report = try {
            val relocation = ChunkRelocation(
                levelDir = levelDir,
                stagedLevelDir = stagedLevelDir,
                workDir = staging.resolve(WORK_DIRECTORY),
                offset = placement.offset,
                tool = tool,
            ).run()
            stampAsMerged(placement)
            MergeReport(placement, relocation)
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
        val marker = staging.resolve(WorldMerge.MARKER_FILE)
        Files.createDirectories(marker.parent)
        Files.writeString(
            marker,
            "{\"mergedAt\":\"${Instant.now()}\"," +
                "\"offsetX\":${placement.offset.x},\"offsetZ\":${placement.offset.z}}\n",
        )
    }

    /**
     * The staged merge moved into the live save.
     *
     * Chunk data is moved in file by file and never over an existing one. It
     * cannot collide — the placement search proved every destination region file
     * free before the relocation ran, and the offset moves whole files — so a
     * collision here means the save changed underneath the merge, and stopping
     * with the staging directory intact is the only safe answer to that.
     */
    private fun commit() {
        val moves = staged(stagedLevelDir, levelDir) +
            staged(staging.resolve(WorldMerge.MOD_DIRECTORY), plan.targetDir.resolve(WorldMerge.MOD_DIRECTORY))
        // Every destination is checked before any file moves, so a collision
        // leaves the save exactly as it was rather than half-merged.
        val clash = moves.firstOrNull { (_, destination) -> Files.exists(destination) }
        if (clash != null) {
            throw IllegalStateException(
                "${clash.second} already exists, so the merge stopped rather than overwrite it. " +
                    "Nothing has been changed in ${plan.targetDir}, and $staging still holds " +
                    "everything the merge built.",
            )
        }
        for ((file, destination) in moves) {
            Files.createDirectories(destination.parent)
            Files.move(file, destination)
        }
        deleteRecursively(staging)
    }

    /** Every staged file under [from], paired with where in [to] it belongs. */
    private fun staged(from: Path, to: Path): List<Pair<Path, Path>> {
        if (!Files.isDirectory(from)) return emptyList()
        return Files.walk(from).use { paths ->
            paths.filter(Files::isRegularFile)
                .map { it to to.resolve(from.relativize(it).toString()) }
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
