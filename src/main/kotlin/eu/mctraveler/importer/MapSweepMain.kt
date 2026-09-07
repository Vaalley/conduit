package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap

/**
 * The map sweep, run against a save the merge has already committed.
 *
 * [MapSweep] is a phase of the merge, and on the next migration it will run
 * inside one. This is how it reaches the migration that has already happened:
 * the maps were missed, the merge stamped itself as done, and re-running the
 * whole merge to pick them up is neither possible nor sane.
 *
 * The offset is read from the stamp the merge left rather than typed again.
 * Getting it wrong is the failure that matters here — a map moved by the wrong
 * distance points at real ground and lies — and an operator re-entering `802816`
 * from memory at eight in the morning is exactly how that happens.
 *
 * **It reports by default and writes only when told to.** With `--apply` each
 * rewritten file is built beside the save and moved into place afterwards, so a
 * failure halfway through leaves the maps as they were.
 *
 * ```
 * ./gradlew mapSweep --args="/srv/mctraveler-server"            # says what it would do
 * ./gradlew mapSweep --args="/srv/mctraveler-server --apply"    # does it
 * ```
 *
 * **Stop the server first.** A loaded map lives in memory and is written back on
 * the next save, so rewriting the file underneath a running server is work the
 * server will quietly undo.
 */
object MapSweepMain {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("usage: <target dir> [--level-name <name>] [--apply]")
            exitProcess(2)
        }
        val target = Path.of(args[0])
        val apply = args.contains("--apply")
        val levelName = args.indexOf("--level-name").takeIf { it >= 0 }?.let { args[it + 1] } ?: "world"

        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()

        val marker = target.resolve(WorldMerge.MARKER_FILE)
        if (Files.notExists(marker)) {
            System.err.println("$marker is not there, so this save has not been merged and there is nothing to move")
            exitProcess(1)
        }
        val stamp = Files.readString(marker)
        val offset = MergeOffset(number(stamp, "offsetX"), number(stamp, "offsetZ"))
        println("merged at the offset the stamp records: ${offset.describe(eu.mctraveler.worlds.DimensionRole.OVERWORLD)}")

        val maps = target.resolve(levelName).resolve(MAPS)
        val staging = target.resolve(STAGING)
        if (apply && Files.exists(staging)) {
            System.err.println("$staging is left over from an interrupted sweep; look at it, remove it, run again")
            exitProcess(1)
        }

        val report = if (apply) {
            Files.createDirectories(staging)
            MapSweep(maps, offset) { staging.resolve(it.fileName) }.sweep()
        } else {
            MapSweep(maps, offset).sweep()
        }

        report.lines().forEach(::println)

        if (!apply) {
            println()
            println("Nothing was written. Pass --apply to move them, with the server stopped.")
            return
        }
        // Only once every file has been rewritten successfully. Moving them in as
        // they were produced would leave a half-swept save behind on a failure,
        // and half-swept is the state nobody can reason about afterwards.
        var moved = 0
        Files.newDirectoryStream(staging).use { staged ->
            for (file in staged) {
                Files.move(file, maps.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
                moved++
            }
        }
        Files.deleteIfExists(staging)
        println()
        println("$moved map files moved into place.")
    }

    /** One number out of the merge stamp, which is small enough not to want a parser. */
    private fun number(json: String, field: String): Int =
        Regex("\"$field\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toInt()
            ?: throw IllegalStateException("the merge stamp does not record $field: $json")

    private const val MAPS = "data/minecraft/maps"
    private const val STAGING = ".mctraveler-maps"
}
