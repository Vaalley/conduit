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

    /**
     * The merge's banked positions, which `/switch` reads back to tell a player
     * where their other base went. Bound to the file at server start rather than
     * to its contents — nothing is read until the first player asks, and an
     * unmerged server has no file to read at all.
     */
    var bankedPositions: BankedPositions? = null
        private set

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            val persistence = checkNotNull(MCTraveler.persistence)
            worlds = Worlds(server, persistence.players)
            bankedPositions = BankedPositions(persistence.root.resolve(BankedPositions.FILE_NAME))
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            SwitchCommand.register(dispatcher) { checkNotNull(bankedPositions) }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            checkNotNull(worlds).handleLogin(handler.player)
        }
    }
}
