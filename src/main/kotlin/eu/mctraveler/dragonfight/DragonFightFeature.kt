package eu.mctraveler.dragonfight

import eu.mctraveler.MCTraveler
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.portal.TeleportTransition

object DragonFightFeature {
    const val NAME = "dragonfight"
    const val STATE_FILE = "dragonfight-state.json"

    var state: DragonFightState? = null
        private set
    var service: DragonFightService? = null
        private set

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            val persistence = checkNotNull(MCTraveler.persistence)
            state = DragonFightState(persistence.root.resolve(STATE_FILE))
            service = DragonFightService(server, checkNotNull(state))
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            checkNotNull(state).clearArenas()
            ArenaLevels.sweepFolders(server)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            service?.clear()
            service = null
            state = null
        }
        ServerTickEvents.END_SERVER_TICK.register { service?.tick() }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            DragonFightConfig.reload()
            DragonFightCommands.register(dispatcher) { checkNotNull(service) }
        }
    }

    fun isArena(level: ServerLevel): Boolean = ArenaLevels.owner(level.dimension()) != null

    fun arenaExitPortal(level: ServerLevel, entity: Entity): TeleportTransition? =
        service?.exitPortal(level, entity)

    fun arenaGatewayBlocked(level: ServerLevel, entity: Entity) {
        service?.gatewayBlocked(level, entity)
    }
}
