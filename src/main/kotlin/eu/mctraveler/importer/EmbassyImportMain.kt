package eu.mctraveler.importer

import java.nio.file.Path
import kotlin.system.exitProcess
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap

/**
 * The Nucleus import's command line (`./gradlew importNucleus --args="…"`, see
 * `docs/nucleus-import.md`). It only parses arguments and reports;
 * [EmbassyImport] is the import.
 *
 * The sibling of [ImporterMain], and deliberately its twin: same argument
 * grammar, same refusal wording, same "nothing was written" guarantee. The two
 * are run one after the other on the same cutover night.
 */
object EmbassyImportMain {

    private val USAGE = """
        Imports the retired Nucleus server's embassies into an already-migrated Fabric run directory.

        Run it with the server STOPPED, after `migrate` and BEFORE the new build's first boot.

          --old <dir>          the Nucleus server directory (embassies/, plugins/MCTravelerNucleus/, world/)
          --target <dir>       the migrated Fabric server run directory (regions.json, mctraveler/, world/)
          --level-name <name>  the level directory to write into        [default: world]
          --worlds copy|move   how the embassies chunk data reaches the new save [default: copy]
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

        // The import reads and writes vanilla save files, which need the game's
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
            val report = EmbassyImport(plan).run()
            println("Imported ${plan.oldDir} into ${plan.targetDir}:")
            report.lines().forEach { println("  $it") }
            if (report.playersImported == 0) {
                println(
                    "\nNo player carried the Nucleus crystal tags. On the real deployment 25 saves do — " +
                        "if this was that deployment, check --old points at the Nucleus server whose " +
                        "world/playerdata/ holds them.",
                )
            }
            if (report.unknownDestinationWorlds.isNotEmpty()) {
                println(
                    "\nSome imported embassies point at a world this server does not have — their " +
                        "anchors have nowhere to send anyone. They are listed above.",
                )
            }
        } catch (refusal: MigrationRefused) {
            System.err.println("Import refused, nothing was written: ${refusal.message}")
            exitProcess(1)
        } catch (failure: Exception) {
            System.err.println("Import failed, nothing was written: ${failure.message}")
            failure.printStackTrace()
            exitProcess(1)
        }
    }

    private fun plan(options: Map<String, String>): EmbassyImportPlan = EmbassyImportPlan(
        oldDir = Path.of(required(options, "old")),
        targetDir = Path.of(required(options, "target")),
        levelName = options["level-name"] ?: "world",
        worldTransfer = when (val mode = options["worlds"] ?: "copy") {
            "copy" -> WorldTransfer.COPY
            "move" -> WorldTransfer.MOVE
            else -> throw IllegalArgumentException("--worlds must be \"copy\" or \"move\", got \"$mode\"")
        },
    )

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
