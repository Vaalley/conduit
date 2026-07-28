package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory

/**
 * Production smoke check (the `prodServer` Gradle task). Only active when the JVM is
 * started with -Dmctraveler.smoke=true: once the real dedicated server has fully
 * started, verify the mod initialized and stop the server so the task exits cleanly.
 * Any failure crashes the server, failing the Gradle task with a non-zero exit.
 */
object SmokeHook : DedicatedServerModInitializer {
    private val LOGGER = LoggerFactory.getLogger("mctraveler-smoke")

    override fun onInitializeServer() {
        if (System.getProperty("mctraveler.smoke") == null) return

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            check(MCTraveler.initialized) {
                "smoke: server started but MCTraveler did not initialize"
            }
            LOGGER.info("MCTraveler prod smoke OK: mod initialized on a real dedicated server; stopping.")
            server.halt(false)
        }
    }
}
