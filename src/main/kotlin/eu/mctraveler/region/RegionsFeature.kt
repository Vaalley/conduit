package eu.mctraveler.region

import eu.mctraveler.MCTraveler
import java.util.UUID
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
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

    /**
     * The username behind a member uuid — the online player's, else the name
     * cache's (deviation 10: a real cache, so member lists are complete).
     * Null only when the name is genuinely unknown, in which case that member
     * is invisible to `/rg locate`, `/rg remove` and the sidebar, exactly as
     * in the Portal.
     */
    fun usernameFor(server: MinecraftServer, uuid: UUID): String? =
        server.playerList.getPlayer(uuid)?.gameProfile?.name
            ?: MCTraveler.persistence?.names?.usernameFor(uuid)

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            service = RegionService(server.serverDirectory.resolve("regions.json"))
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            RegionCommands.register(dispatcher)
        }
        RegionTracker.register()
        // The Portal kept start markers per connection; dropping them on
        // disconnect preserves that lifetime.
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            RegionCommands.clearStartMarker(handler.player.uuid)
        }
    }
}
