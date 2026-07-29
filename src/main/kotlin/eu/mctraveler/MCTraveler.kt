package eu.mctraveler

import eu.mctraveler.chat.ChatFeature
import eu.mctraveler.chat.PrivateMessages
import eu.mctraveler.persistence.PersistenceService
import eu.mctraveler.tablist.TabListFeature
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
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

    /**
     * The Persistence service, created fresh at each server start (never
     * cleared — a stale instance is harmlessly replaced); null until the first
     * server starts.
     */
    var persistence: PersistenceService? = null
        private set

    override fun onInitialize() {
        TabListFeature.register()
        PrivateMessages.register()
        ChatFeature.register()


        initialized = true
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            persistence = PersistenceService(server.serverDirectory.resolve("mctraveler"))
        }
        // Every login refreshes the name cache — the real cache that replaces
        // the Portal's op-only one, so offline lookups know every player.
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            checkNotNull(persistence).names.record(player.uuid, player.gameProfile.name)
        }
        LOGGER.info("MCTraveler initialized")
    }
}
