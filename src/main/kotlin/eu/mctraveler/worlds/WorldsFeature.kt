package eu.mctraveler.worlds

import eu.mctraveler.MCTraveler
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents

/**
 * Wiring for the Worlds service: builds it at server start (after the
 * Persistence service, whose SERVER_STARTING hook registers first in
 * [MCTraveler.onInitialize]), registers `/switch`, and routes every login.
 */
object WorldsFeature {

    /**
     * The live Worlds service — the seam later features (respawn routing,
     * portal routing, regions) reach the topology through. Created fresh at
     * each server start; null until the first server starts.
     */
    var worlds: Worlds? = null
        private set

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            worlds = Worlds(server, checkNotNull(MCTraveler.persistence).players)
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            SwitchCommand.register(dispatcher) { checkNotNull(worlds) }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            checkNotNull(worlds).handleLogin(handler.player)
        }
    }
}
