package eu.mctraveler

import eu.mctraveler.away.AwayFeature
import eu.mctraveler.chat.ChatBridge
import eu.mctraveler.chat.ChatFeature
import eu.mctraveler.chat.PrivateMessages
import eu.mctraveler.crystal.CrystalFeature
import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.geo.GeoIpFeature
import eu.mctraveler.hooks.Hooks
import eu.mctraveler.http.HttpApi
import eu.mctraveler.lodeway.LodewayFeature
import eu.mctraveler.map.MapCommand
import eu.mctraveler.map.MapItemGuard
import eu.mctraveler.notepad.NotepadFeature
import eu.mctraveler.persistence.PersistenceService
import eu.mctraveler.passport.PassportFeature
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.tablist.TabListFeature
import eu.mctraveler.weather.NoRain
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.slf4j.LoggerFactory

/** Mod entrypoint. Only ever runs on a physical (dedicated) server. */
object MCTraveler : ModInitializer {
    const val MOD_ID = "mctraveler"

    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    /** True once the mod has initialized. */
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
        Hooks.impl = MixinHooksImpl
        TabListFeature.register()
        PrivateMessages.register()
        ChatFeature.register()
        GeoIpFeature.register()
        ChatBridge.register()
        AwayFeature.register()
        eu.mctraveler.rank.RankFeature.register()
        NoRain.register()
        eu.mctraveler.text.SignNames.register()


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
        NotepadFeature.register()
        eu.mctraveler.importer.OrphanedSaveClaimFeature.register()
        eu.mctraveler.worlds.WorldsFeature.register()
        RegionsFeature.register()
        EmbassiesFeature.register()
        PassportFeature.register()
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
        LOGGER.info("MCTraveler initialized")
    }
}
