package eu.mctraveler.bootstrap

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

/**
 * Mod entrypoint — the permanent half of the mod (docs/hot-reload.md). Fabric
 * only ever sees this jar; everything gameplay lives in the runtime jar that
 * [RuntimeHost] side-loads and hot-swaps. The mod is `"environment": "server"`
 * in fabric.mod.json, so this only ever runs on a physical (dedicated) server.
 */
object ConduitBootstrap : ModInitializer {
    const val MOD_ID = "mctraveler"

    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        RuntimeHost.initialize()
        LOGGER.info("MCTraveler bootstrap initialized")
    }
}
