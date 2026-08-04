package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo

/**
 * Every chunk of a relocated dimension, read out of the staging area and written
 * back only where something changed.
 *
 * Two phases need exactly this and need it to behave identically:
 * [ChunkCompletion] finishes the coordinates the relocation tool left behind, and
 * [ChunkAudit] then proves none are left. Sharing one reader is what makes the
 * second a real check on the first — a chunk the completion pass could reach but
 * the audit could not would be a gap neither of them could see.
 *
 * **A chunk nothing changed is never re-encoded.** Only chunks a visit reports as
 * modified are written, so the bytes MCA Selector produced are the bytes the merge
 * commits wherever it had nothing to do. That is what lets ticket 04's sampled diff
 * compare relocated terrain against its source without either of these phases
 * standing in the middle of it.
 *
 * **Nothing here touches the live save.** The path handed in is always inside the
 * staging directory, which [MergeStaging] commits with everything else or not at
 * all.
 */
internal class StagedChunks(private val stagedLevelDir: Path) {

    /**
     * Every chunk of every region file under [folder], handed to [visit] in a
     * fixed order. [visit] returns whether it changed the chunk, and only those
     * are written back.
     */
    fun walk(
        folder: Path,
        dimension: ResourceKey<Level>,
        type: String,
        visit: (ChunkPos, CompoundTag) -> Boolean,
    ) = walk(folder, dimension, type, said = null, visit = visit)

    /**
     * As above, saying where it has got to under [said].
     *
     * Both phases that walk every chunk go through here, so one line of progress
     * serves both. It is worth the line: the first live merge spent an hour and
     * three quarters in silence, and working out which phase it was in — let
     * alone how far — took `/proc` sampling and a thread dump against a running
     * JVM. An operator in a downtime window cannot do that, and should not have
     * to (ticket 20).
     *
     * Every file rather than every chunk, and a plain newline rather than a
     * carriage return, because the runbook has operators capture the run with
     * `script` and a progress bar redrawn in place is unreadable afterwards.
     */
    fun walk(
        folder: Path,
        dimension: ResourceKey<Level>,
        type: String,
        said: String?,
        visit: (ChunkPos, CompoundTag) -> Boolean,
    ) {
        if (!Files.isDirectory(folder)) return
        val files = Files.newDirectoryStream(folder, "r.*.mca").use { it.sortedBy(Path::toString) }
        var done = 0
        val started = System.currentTimeMillis()
        for (file in files) {
            if (said != null) {
                done++
                if (done == 1 || done % PROGRESS_EVERY == 0 || done == files.size) {
                    val seconds = (System.currentTimeMillis() - started) / 1000
                    println("  $said: region file $done of ${files.size}  (${seconds}s elapsed)")
                    System.out.flush()
                }
            }
            val at = RegionFilePos.parse(file.fileName.toString()) ?: continue
            val chunks = read(file, folder, dimension, type, at)
            val changed = LinkedHashMap<ChunkPos, CompoundTag>()
            for ((chunk, tag) in chunks) {
                if (visit(chunk, tag)) changed[chunk] = tag
            }
            if (changed.isEmpty()) continue
            region(file, folder, dimension, type).use { region ->
                for ((chunk, tag) in changed) {
                    region.getChunkDataOutputStream(chunk).use { NbtIo.write(tag, it) }
                }
            }
        }
    }

    private fun read(
        file: Path,
        folder: Path,
        dimension: ResourceKey<Level>,
        type: String,
        at: RegionFilePos,
    ): Map<ChunkPos, CompoundTag> {
        val found = LinkedHashMap<ChunkPos, CompoundTag>()
        region(file, folder, dimension, type).use { region ->
            for (localZ in 0 until CHUNKS_PER_REGION) {
                for (localX in 0 until CHUNKS_PER_REGION) {
                    val chunk = ChunkPos(
                        at.x * CHUNKS_PER_REGION + localX,
                        at.z * CHUNKS_PER_REGION + localZ,
                    )
                    if (!region.hasChunk(chunk)) continue
                    region.getChunkDataInputStream(chunk)?.use { found[chunk] = NbtIo.read(it) }
                }
            }
        }
        return found
    }

    /**
     * A region file opened through the server's own reader, so that the sector
     * layout and the compression the merge reads and writes are the ones the
     * server will meet rather than a second implementation of them.
     */
    private fun region(file: Path, folder: Path, dimension: ResourceKey<Level>, type: String) =
        RegionFile(RegionStorageInfo(stagedLevelDir.fileName.toString(), dimension, type), file, folder, false)

    companion object {
        private const val CHUNKS_PER_REGION = 32

        /**
         * How many region files pass between progress lines.
         *
         * The live save stages some forty thousand of them, so this is around
         * four hundred lines a walk: enough that a stalled phase is obvious
         * within a minute or two, few enough that the captured log stays
         * something a person can read.
         */
        private const val PROGRESS_EVERY = 100

        /** The three folders a dimension's chunk data is split across. */
        const val TERRAIN = "region"
        const val ENTITIES = "entities"
        const val POI = "poi"

        // The `type` a RegionStorageInfo carries, which is what the server's own
        // error messages name a file by.
        const val TERRAIN_TYPE = "chunk"
        const val ENTITIES_TYPE = "entities"
        const val POI_TYPE = "poi"
    }
}
