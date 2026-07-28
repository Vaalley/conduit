package eu.mctraveler

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

/**
 * Mod entrypoint. The mod is declared `"environment": "server"` in fabric.mod.json,
 * so this only ever runs on a physical (dedicated) server.
 */
object MCTraveler : ModInitializer {
    const val MOD_ID = "mctraveler"

    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    /** True once [onInitialize] has run — the scaffold's "the mod is alive" signal. */
    var initialized: Boolean = false
        private set

    override fun onInitialize() {
        initialized = true
        LOGGER.info("MCTraveler initialized")
    }
}
