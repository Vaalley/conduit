package eu.mctraveler.importer

import java.nio.file.Path
import kotlin.system.exitProcess
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap

/**
 * The migration tool's command line (`./gradlew migrate --args="…"`, see
 * `docs/migration.md`). It only parses arguments and reports; [PortalImport]
 * is the migration.
 */
object ImporterMain {

    private val USAGE = """
        Migrates an MCTraveler Portal deployment into a Fabric server run directory.

          --portal <dir>       the Portal's working directory (players/, uuid-cache.json, regions.json)
          --target <dir>       the Fabric server run directory to write into (must hold no migrated save)
          --primary <dir>      the Primary backend's server directory  [default: <portal>/minecraft-server/primary]
          --secondary <dir>    the Secondary backend's server directory [default: <portal>/minecraft-server/secondary]
          --level-name <name>  the level directory to write             [default: world]
          --identities <file>  JSON of "username": "<mojang uuid>" for players the Portal's cache never saw
          --skip-unidentified  quarantine saves whose player cannot be identified (claimed at their
                               owner's next login) instead of refusing to migrate
          --worlds copy|move   how the chunk data and quarantined saves reach the new save [default: copy]
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

        // The migration reads and writes vanilla save files, which need the
        // game's registries even with no server running.
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()

        val plan = try {
            plan(options)
        } catch (invalid: IllegalArgumentException) {
            System.err.println("${invalid.message}\n\n$USAGE")
            exitProcess(2)
        }

        try {
            val report = PortalImport(plan).run()
            println("Migrated ${plan.portalDir} into ${plan.targetDir}:")
            report.lines().forEach { println("  $it") }
            if (report.quarantinedSaves > 0) {
                println(
                    "\n${report.quarantinedSaves} save(s) went into " +
                        "mctraveler/${SaveQuarantine.DIRECTORY}/ because nobody could be named for them. " +
                        "Each is claimed automatically the first time its owner logs in — watch the log " +
                        "for \"orphaned-save claim\" lines, and for any claim that could not be made.",
                )
            }
            if (report.unidentifiedOperators.isNotEmpty()) {
                println("\nSome operators were deliberately left behind — see the lines above.")
            }
        } catch (refusal: MigrationRefused) {
            System.err.println("Migration refused, nothing was written: ${refusal.message}")
            exitProcess(1)
        } catch (failure: Exception) {
            System.err.println("Migration failed, nothing was written: ${failure.message}")
            failure.printStackTrace()
            exitProcess(1)
        }
    }

    private fun plan(options: Map<String, String>): ImportPlan {
        val portal = Path.of(required(options, "portal"))
        return ImportPlan(
            portalDir = portal,
            primaryServerDir = options["primary"]?.let(Path::of)
                ?: portal.resolve("minecraft-server/primary"),
            secondaryServerDir = options["secondary"]?.let(Path::of)
                ?: portal.resolve("minecraft-server/secondary"),
            targetDir = Path.of(required(options, "target")),
            levelName = options["level-name"] ?: "world",
            identitiesFile = options["identities"]?.let(Path::of),
            skipUnidentified = options.containsKey("skip-unidentified"),
            worldTransfer = when (val mode = options["worlds"] ?: "copy") {
                "copy" -> WorldTransfer.COPY
                "move" -> WorldTransfer.MOVE
                else -> throw IllegalArgumentException("--worlds must be \"copy\" or \"move\", got \"$mode\"")
            },
        )
    }

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
