package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path

/**
 * MCA Selector, the tool that actually moves Secondary's chunks.
 *
 * The merge does not rewrite chunk NBT itself. MCA Selector has relocated chunks
 * for a decade, tracks the current Minecraft version, and knows every coordinate
 * a chunk can hide one in — block entities, ticks, structure references, entity
 * positions, point-of-interest records. Reimplementing that against a format
 * that changes every release is the kind of work the merge spec's "Relocation"
 * note declines on purpose.
 *
 * **It is a tool we run, not a library we link.** Its own dependency tree —
 * JavaFX, Groovy, LevelDB — never reaches the mod's compile classpath. The build
 * resolves it into a configuration of its own, verifies it against a pinned
 * checksum and hands the jar's path down as [JAR_PROPERTY]; everything here goes
 * through a subprocess. That is also what lets the tests drive the real thing on
 * every build rather than a stand-in (merge spec, "Testing Decisions").
 *
 * The jobs below are the only two the merge asks for, spelled out as the tool's
 * own flags so that what runs on the night can be read off the source and typed
 * by hand if it ever has to be.
 */
class McaSelector(private val jar: Path, private val java: Path = currentJava()) {

    /**
     * The chunks under [from] that vanilla has finished generating, written to
     * [into] as MCA Selector's own region/chunk CSV.
     *
     * This is what drops Secondary's frontier (merge spec, User Story 14): a
     * chunk whose status is anything but [FULL_STATUS] is half-generated, and
     * relocating it would strand a partial chunk from one seed inside a map
     * generated from another. Selecting first and relocating the selection
     * afterwards means the incomplete chunks are never written anywhere at all,
     * rather than moved and then cleaned up.
     *
     * The selection is taken from the terrain folder, so a chunk that somehow has
     * entity or point-of-interest data but no terrain is not carried. That is the
     * conservative direction: such a chunk has no blocks to stand on.
     */
    fun select(from: Path, into: Path): String = run(
        "selecting the finished chunks of $from",
        "--mode", "select",
        "--world", from.toString(),
        "--query", "Status = $FULL_STATUS",
        "--output", into.toString(),
    )

    /**
     * The chunks [selection] names, copied out of [from] and into [into], moved
     * [chunksX] chunks east and [chunksZ] chunks south.
     *
     * The offset is in **chunks** — MCA Selector adds it to a chunk coordinate,
     * not a block one — so callers divide the merge's block offset down before
     * they get here. There is deliberately no `--y-offset`: a merge offset is
     * horizontal and cannot express anything else ([MergeGeometry]).
     *
     * `--overwrite` is deliberately not passed. Nothing should be in the way —
     * the placement search proved the destination free of Primary chunk data
     * before any of this ran — and if something ever is, the merge would rather
     * leave it standing and be caught by [ChunkRelocation]'s count than quietly
     * destroy terrain a player built.
     *
     * Note that MCA Selector treats a target holding no region files at all as
     * "no files" and returns **successfully** having done nothing, so callers
     * must check what arrived rather than trust the exit status.
     */
    fun relocate(from: Path, into: Path, selection: Path, chunksX: Int, chunksZ: Int): String = run(
        "relocating $from into $into",
        "--mode", "import",
        "--world", into.toString(),
        "--source-world", from.toString(),
        "--source-selection", selection.toString(),
        "--x-offset", chunksX.toString(),
        "--z-offset", chunksZ.toString(),
    )

    /**
     * One run of the tool, with its whole output captured.
     *
     * A failure attaches that output verbatim rather than summarising it: this is
     * a tool we did not write, run once, in a downtime window, and the operator
     * standing over it needs what it actually said. Nothing is moved into place
     * on this path — the caller is inside the staging discipline, and throwing
     * here is what abandons the merge (ticket 02).
     */
    private fun run(what: String, vararg arguments: String): String {
        val command = listOf(java.toString(), "-jar", jar.toString()) + arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val status = process.waitFor()
        if (status != 0) {
            throw IllegalStateException(
                "MCA Selector failed while $what (exit $status). Nothing has been moved into place.\n" +
                    "  ${command.joinToString(" ")}\n" +
                    output.prependIndent("  "),
            )
        }
        return output
    }

    companion object {
        /** The status vanilla gives a chunk once it has finished generating it. */
        const val FULL_STATUS = "full"

        /** Where the build tells the merge it put the verified jar; see gradle/merge-worlds.gradle.kts. */
        const val JAR_PROPERTY = "mctraveler.mcaSelectorJar"

        /**
         * The tool as the build resolved it.
         *
         * There is no fallback to a jar found on the filesystem or to one an
         * operator downloaded: the pinned, checksum-verified artifact is the only
         * one the merge will run, because a merge is not repeatable and the
         * version that relocated the map is a thing we have to be able to state
         * afterwards.
         */
        fun resolved(): McaSelector {
            val configured = System.getProperty(JAR_PROPERTY)
            if (configured.isNullOrBlank()) {
                throw IllegalStateException(
                    "the merge does not know where MCA Selector is: no \"$JAR_PROPERTY\" was set. " +
                        "Run the merge through the build (./gradlew mergeWorlds --args=\"…\"), which " +
                        "resolves the tool at its pinned version and verifies its checksum first.",
                )
            }
            val jar = Path.of(configured)
            if (!Files.isRegularFile(jar)) {
                throw IllegalStateException(
                    "MCA Selector is not at $jar, where \"$JAR_PROPERTY\" says it is — " +
                        "run ./gradlew provideMcaSelector to resolve and verify it again.",
                )
            }
            return McaSelector(jar)
        }

        /**
         * The JVM already running, rather than whatever `java` a login shell
         * would find. The merge is run over SSH against a production box where
         * the two are not reliably the same.
         */
        private fun currentJava(): Path =
            Path.of(System.getProperty("java.home"), "bin", "java")
    }
}
