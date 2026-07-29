package eu.mctraveler.region

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.level.ServerPlayer

/**
 * Wiring for the Region service: brings the service up with the server
 * (regions live in `regions.json` in the run directory, the Portal's path)
 * and registers the `/region` + `/rg` commands.
 *
 * Later region tickets (membership/scoreboard, protection) build on
 * [service] and [isAdmin].
 */
object RegionsFeature {

    /** The live Region service; null until the first server starts. */
    var service: RegionService? = null
        private set

    fun requireService(): RegionService =
        checkNotNull(service) { "the Region service is not started" }

    /**
     * Admin means vanilla operator status (spec User Story 41): the vanilla
     * ops list is the single source of truth, managed by vanilla `/op` and
     * `/deop`. Checked against the list itself rather than a permission
     * level so the answer can never disagree with `ops.json`.
     */
    fun isAdmin(player: ServerPlayer): Boolean =
        player.level().server.playerList.isOp(player.nameAndId())

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            service = RegionService(server.serverDirectory.resolve("regions.json"))
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            RegionCommands.register(dispatcher)
        }
        // The Portal kept start markers per connection; dropping them on
        // disconnect preserves that lifetime.
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            RegionCommands.clearStartMarker(handler.player.uuid)
        }
    }
}
