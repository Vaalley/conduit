package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * That asking MCA Selector which chunks to move gives the same answer every time.
 *
 * This is not a property of the tool the merge could take on trust. The released
 * 2.8 hands `Selection::merge` to `ChunkFilterSelector.selectFilter` as the
 * per-region-file callback, and that method mutates a non-thread-safe fastutil map
 * from every job in the process pool at once, so a selection silently came back
 * short — never by a chunk or two, always by an entire region file's worth, and
 * always with exit 0. Measured at five losses in 120 runs before the fix, and none
 * in 120 with `--process-threads 1`. In production that is a player's base left
 * behind in a World that is about to be retired, with the merge reporting success.
 *
 * The lock is in the patched build the tool is pinned to (ticket 16), so what this
 * suite guards is that we keep running a build that has it. It asserts by
 * *repetition* rather than by inspection, because a race is not visible in any one
 * run: [RUNS] identical selections is the whole claim, and one short one is a
 * failure however many passed beside it.
 *
 * The count is deliberately small enough to belong in every build. It is not on its
 * own enough to catch a one-in-twenty defect — thirty runs would miss one about a
 * fifth of the time — so it is a regression guard rather than the original
 * evidence, and it can be turned up for a longer look with
 * `-Dmctraveler.selectionRuns=<n>`.
 */
class McaSelectorSelectionTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        /** How many times the selection is asked for; see the class comment. */
        private val RUNS = Integer.getInteger("mctraveler.selectionRuns", 30)

        /**
         * Three finished chunks across two region files, plus a frontier chunk that
         * must not be selected. Two files is what the race needs to be able to lose
         * one, and it is what lost one when this was first reproduced.
         */
        private val CHUNKS = listOf(
            SyntheticChunks.Chunk(0, 0),
            SyntheticChunks.Chunk(5, 3),
            SyntheticChunks.Chunk(32, 0),
            SyntheticChunks.Chunk(40, 0, status = SyntheticChunks.PROTO),
        )
    }

    @TempDir
    lateinit var dir: Path

    private lateinit var from: Path

    @BeforeEach
    fun writeSecondary() {
        val levelDir = dir.resolve("world")
        val dimension = WorldLayout.SECONDARY.dimension(DimensionRole.OVERWORLD)
        SyntheticChunks.write(levelDir, dimension, CHUNKS)
        from = Footprint.storageFolder(levelDir, dimension)
    }

    @Test
    fun `the same save selects the same chunks every time`() {
        val tool = McaSelector.resolved()
        val expected = "0;0;0;0\n0;0;5;3\n1;0;32;0\n"

        val answers = (1..RUNS).map { run ->
            val into = dir.resolve("selection-$run.csv")
            tool.select(from, into)
            Files.readString(into)
        }

        // Named by their content rather than counted, so a failure says which
        // chunks went missing rather than only that the answers disagreed.
        assertEquals(
            mapOf(sorted(expected) to RUNS),
            answers.groupingBy(::sorted).eachCount(),
            "the selection is not deterministic across $RUNS runs",
        )
    }

    @Test
    fun `a frontier chunk is never selected, however many times it is asked`() {
        val tool = McaSelector.resolved()

        for (run in 1..RUNS) {
            val into = dir.resolve("frontier-$run.csv")
            tool.select(from, into)
            assertEquals(
                emptyList<String>(),
                Files.readAllLines(into).filter { it.endsWith(";40;0") },
                "run $run selected the frontier chunk",
            )
        }
    }

    /**
     * A selection's lines in a fixed order.
     *
     * The tool writes them per region file, and which file finishes first is a
     * genuine race that costs nothing: the CSV is a set, and the import reads it as
     * one. Ordering is not what this suite is about, and asserting it would make a
     * red test out of the one non-determinism that is harmless.
     */
    private fun sorted(csv: String): String = csv.trim().lines().sorted().joinToString("\n")
}
