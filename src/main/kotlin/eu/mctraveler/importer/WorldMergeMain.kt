package eu.mctraveler.importer

import java.nio.file.Path
import kotlin.system.exitProcess
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap

/**
 * The merge's command line (`./gradlew mergeWorlds --args="…"`). It only parses
 * arguments and reports; [WorldMerge] is the merge.
 *
 * The third of the cutover tools' entry points and deliberately their sibling:
 * same argument grammar, same refusal wording, same "nothing was written"
 * guarantee — [ImporterMain] and [EmbassyImportMain] have already taught the
 * operator what to expect from one of these.
 */
object WorldMergeMain {

    private val USAGE = """
        Merges the Secondary World into Primary, against a live server run directory.

        Run it with the server STOPPED. It measures both Worlds, chooses where Secondary's
        landmass goes, relocates Secondary's overworld and nether chunk data into Primary's at
        that offset, and rewrites everything that recorded a place in Secondary to name its new
        one. Everything is built in a staging directory and moved into place only if the whole
        merge succeeds; Secondary's End is discarded rather than moved.

        Plan it first with --plan-only, check the placement against the real map, then run it
        for real with the offset it chose.

          --target <dir>        the live server run directory (regions.json, mctraveler/, world/)
          --plan-only           choose the offset, print it and write NOTHING AT ALL
          --level-name <name>   the level directory to work in         [default: world]
          --clearance <blocks>  empty ground to leave around the landmass, in NETHER blocks;
                                the overworld is given eight times as much
                                                                       [default: ${WorldMerge.DEFAULT_CLEARANCE}]
          --offset <x>,<z>      place Secondary at this offset instead of searching for one.
                                It is checked by the same test a searched offset passes.
                                Both axes must be multiples of ${MergeGeometry.OFFSET_ALIGNMENT}
          --search-limit <n>    how many ${MergeGeometry.OFFSET_ALIGNMENT}-block steps out to look
                                                                       [default: ${WorldMerge.DEFAULT_SEARCH_LIMIT}]
          ${MergeEnd.OPT_IN}    go ahead even though Regions, players or Embassy destinations
                                are still anchored in Secondary's End. Their builds there are
                                DELETED. Run without it first and read what it names
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        val options = try {
            options(args)
        } catch (invalid: IllegalArgumentException) {
            System.err.println("${invalid.message}\n\n$USAGE")
            exitProcess(2)
        }
        if (options.containsKey("help")) {
            println(USAGE)
            return
        }

        // The merge reads and writes vanilla save files, which need the game's
        // registries even with no server running.
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()

        val plan = try {
            plan(options)
        } catch (invalid: IllegalArgumentException) {
            System.err.println("${invalid.message}\n\n$USAGE")
            exitProcess(2)
        }

        try {
            val report = WorldMerge(plan).run()
            val what = if (plan.planOnly) "Planned" else "Merged"
            println("$what the merge of Secondary into Primary in ${plan.targetDir}:")
            report.lines().forEach { println("  $it") }
            if (plan.planOnly) {
                println(
                    "\nNothing was written. Check that distance against the live map before the real " +
                        "run — Secondary has grown since the Portal cutover — and pass " +
                        "--offset ${report.offset.x},${report.offset.z} when you run it for real, " +
                        "so the rehearsal and the night put the landmass in the same place.",
                )
            } else {
                println(
                    "\nThe merge is committed and ${plan.targetDir} now carries the merge stamp, so " +
                        "this will refuse to run again. Secondary's End and its level-wide saved " +
                        "data were discarded rather than moved.",
                )
            }
        } catch (refusal: MigrationRefused) {
            System.err.println("Merge refused, nothing was written: ${refusal.message}")
            exitProcess(1)
        } catch (failure: Exception) {
            System.err.println("Merge failed, nothing was written: ${failure.message}")
            failure.printStackTrace()
            exitProcess(1)
        }
    }

    private fun plan(options: Map<String, String>): MergePlan = MergePlan(
        targetDir = Path.of(required(options, "target")),
        levelName = options["level-name"] ?: "world",
        clearance = options["clearance"]?.let { number("clearance", it) } ?: WorldMerge.DEFAULT_CLEARANCE,
        offset = options["offset"]?.let(::offset),
        searchLimit = options["search-limit"]?.let { number("search-limit", it) }
            ?: WorldMerge.DEFAULT_SEARCH_LIMIT,
        planOnly = options.containsKey("plan-only"),
        acceptEndLoss = options.containsKey(MergeEnd.OPT_IN.removePrefix("--")),
    )

    /**
     * `--offset <x>,<z>`, in overworld blocks. [MergeOffset] rejects anything off
     * the lattice, and its complaint is the one the operator sees.
     */
    private fun offset(value: String): MergeOffset {
        val axes = value.split(",")
        require(axes.size == 2) { "--offset must be \"<x>,<z>\", got \"$value\"" }
        return MergeOffset(number("offset", axes[0].trim()), number("offset", axes[1].trim()))
    }

    private fun number(name: String, value: String): Int =
        value.toIntOrNull() ?: throw IllegalArgumentException("--$name wants a whole number, got \"$value\"")

    private fun required(options: Map<String, String>, name: String): String =
        options[name]?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("--$name is required")

    /** `--name value` pairs, plus valueless flags mapped to the empty string. */
    private fun options(args: Array<String>): Map<String, String> {
        val options = LinkedHashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val argument = args[i]
            require(argument.startsWith("--")) { "unexpected argument \"$argument\"" }
            val name = argument.removePrefix("--")
            val value = args.getOrNull(i + 1)
            if (value == null || value.startsWith("--")) {
                options[name] = ""
                i++
            } else {
                options[name] = value
                i += 2
            }
        }
        return options
    }
}
