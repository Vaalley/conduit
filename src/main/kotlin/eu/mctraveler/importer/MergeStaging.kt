package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The merge's staging discipline (merge spec, "Staging"), which is the whole
 * reason a failed merge needs no rollback: everything is read, converted and
 * checked first, output is built under a staging directory inside the target,
 * and only a complete merge is moved into place.
 *
 * It differs from [PortalImport]'s and [EmbassyImport]'s in one way that matters.
 * They build a *new* save beside nothing and rename it in; the merge writes into
 * a save that is already live and already full, so every file it produces has a
 * live twin it replaces. Staging is therefore "build each changed file at the
 * path it will occupy, mirroring the run directory's own layout" — and
 * committing is moving that mirror over the original, file by file.
 *
 * The corollary is the guarantee the sweeps rest on: a file that is never staged
 * is never touched. Staging only what actually changed is what lets a player who
 * was already in Primary come out of the merge byte-for-byte as they went in —
 * not rewritten to an identical value, but never opened for writing at all.
 */
class MergeStaging private constructor(private val target: Path, private val root: Path) {

    /**
     * Where [live] — a file in the run directory — is built before the merge
     * commits, with its parent directories made ready. Staging a file is the
     * decision to replace its live twin; everything not staged survives
     * untouched.
     */
    fun stage(live: Path): Path {
        val staged = root.resolve(target.relativize(live).toString())
        Files.createDirectories(staged.parent)
        return staged
    }

    /** Moves everything built here over its live twin, then clears the staging tree. */
    private fun moveIntoPlace() {
        // Collected before anything moves: the walk is lazy, and moving files out
        // from under it would be reading a directory tree while rewriting it.
        val built = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }
        for (file in built) {
            val destination = target.resolve(root.relativize(file).toString())
            Files.createDirectories(destination.parent)
            Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        deleteRecursively(root)
    }

    companion object {
        /**
         * Runs [work] against a staging directory at [root] inside [target] and,
         * only if it returns, moves everything it built into place.
         *
         * A refusal or a failure anywhere inside [work] takes the half-built tree
         * with it and leaves the run directory exactly as it was. Deleting it is
         * safe here in a way it is not for [PortalImport], which can be holding
         * the only copy of moved data: the merge never moves anything out of the
         * live save, it only ever reads it, so nothing staged is the last copy of
         * anything. A run killed outright leaves the tree behind instead, and
         * [WorldMerge] refuses over it rather than reusing it — it is then the
         * only evidence of what the dead run had built (merge spec, User Story 50).
         */
        fun <T> commit(target: Path, root: Path, work: (MergeStaging) -> T): T {
            Files.createDirectories(root)
            val staging = MergeStaging(target, root)
            val answer = try {
                work(staging)
            } catch (failure: Throwable) {
                deleteRecursively(root)
                throw failure
            }
            staging.moveIntoPlace()
            return answer
        }

        private fun deleteRecursively(directory: Path) {
            if (Files.notExists(directory)) return
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
