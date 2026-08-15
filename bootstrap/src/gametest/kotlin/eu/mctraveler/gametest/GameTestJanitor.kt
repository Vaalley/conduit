package eu.mctraveler.gametest

import java.nio.file.Files
import java.nio.file.Path
import net.fabricmc.api.DedicatedServerModInitializer

/**
 * Keeps headless gametest runs hermetic. Loom reuses the gametest run
 * directory between invocations, but region tests assume the run's regions
 * start empty (structures land at the same coordinates every run, so stale
 * regions from a previous run would collide). Active only under the gametest
 * runner (the `fabric-api.gametest` system property) — never on a real
 * server, and mod init runs before the server loads the file.
 */
object GameTestJanitor : DedicatedServerModInitializer {
    override fun onInitializeServer() {
        if (System.getProperty("fabric-api.gametest") == null) return
        // The run directory is the process working directory at server boot.
        Files.deleteIfExists(Path.of("regions.json"))
    }
}
