package eu.mctraveler.worlds

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents

/**
 * Wiring for what is left of the Worlds subsystem: the [TeleportCountdown] every
 * teleport goes through.
 *
 * There used to be a Worlds service here — the two-World topology, Travel, the
 * Per-World Bucket, and a login hook that routed every arriving player into the
 * World their record named — and, after the merge, a `/switch` signpost. The merge
 * retired the first, because there is one map now and nothing left to route
 * between, and the signpost has since been removed too.
 *
 * The name is kept deliberately. This still wires the `worlds` package, and
 * renaming it without renaming the package would be half a rename that tells a
 * future reader less than the KDoc does.
 */
object WorldsFeature {

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(TeleportCountdown::tick)
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> TeleportCountdown.forget(handler.player.uuid) }
        ServerLifecycleEvents.SERVER_STOPPED.register { TeleportCountdown.clear() }
    }
}
