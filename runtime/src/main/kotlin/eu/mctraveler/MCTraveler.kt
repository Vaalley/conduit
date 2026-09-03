package eu.mctraveler

import eu.mctraveler.away.AwayFeature
import eu.mctraveler.chat.ChatBridge
import eu.mctraveler.chat.ChatFeature
import eu.mctraveler.chat.PrivateMessages
import eu.mctraveler.crystal.CrystalFeature
import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.geo.GeoIpFeature
import eu.mctraveler.http.HttpApi
import eu.mctraveler.lodeway.LodewayFeature
import eu.mctraveler.map.MapCommand
import eu.mctraveler.map.MapItemGuard
import eu.mctraveler.notepad.NotepadFeature
import eu.mctraveler.persistence.PersistenceService
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.tablist.TabListFeature
import eu.mctraveler.weather.NoRain
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.slf4j.LoggerFactory

/**
 * The runtime's feature roster. No longer the Fabric entrypoint — the bootstrap
 * jar side-loads this runtime and calls [start] via [ConduitRuntimeImpl]
 * (docs/hot-reload.md), on mod init for a cold start or mid-game on a hot
 * reload. Only ever runs on a physical (dedicated) server.
 */
object MCTraveler {
    const val MOD_ID = "mctraveler"

    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    /** True once [start] has run — the scaffold's "the runtime is alive" signal. */
    var initialized: Boolean = false
        private set

    /**
     * The Persistence service, created fresh at each server start (never
     * cleared — a stale instance is harmlessly replaced); null until the first
     * server starts.
     */
    var persistence: PersistenceService? = null
        private set

    fun start() {
        TabListFeature.register()
        PrivateMessages.register()
        ChatFeature.register()
        GeoIpFeature.register()
        ChatBridge.register()
        AwayFeature.register()
        NoRain.register()


        initialized = true
        ServerLifecycleEvents.SERVER_STARTING.reloadable.register { server ->
            persistence = PersistenceService(server.serverDirectory.resolve("mctraveler"))
        }
        // Every login refreshes the name cache — the real cache that replaces
        // the Portal's op-only one, so offline lookups know every player.
        ServerPlayConnectionEvents.JOIN.reloadable.register { handler, _, _ ->
            val player = handler.player
            checkNotNull(persistence).names.record(player.uuid, player.gameProfile.name)
        }
        // Players already online when this runtime hot-loads never fire JOIN:
        // record them here so offline lookups keep knowing everyone.
        Conduit.onHotActivate { server ->
            server.playerList.players.forEach { player ->
                checkNotNull(persistence).names.record(player.uuid, player.gameProfile.name)
            }
        }
        NotepadFeature.register()
        eu.mctraveler.importer.OrphanedSaveClaimFeature.register()
        eu.mctraveler.worlds.WorldsFeature.register()
        RegionsFeature.register()
        EmbassiesFeature.register()
        // After ChatFeature: its DISCONNECT handler must decide the leave line
        // before this one clears the vanish state (issue #47).
        eu.mctraveler.vanish.VanishFeature.register()
        // After the regions it publishes: it subscribes to the live service at
        // SERVER_STARTED, and the service is created by the handler above.
        LodewayFeature.register()
        CrystalFeature.register()
        HttpApi.register()
        MapCommand.register()
        MapItemGuard.register()
        LOGGER.info("MCTraveler runtime started")
    }
}
