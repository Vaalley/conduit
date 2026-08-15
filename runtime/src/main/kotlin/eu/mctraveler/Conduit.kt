package eu.mctraveler

import eu.mctraveler.bootstrap.RuntimeContext
import net.fabricmc.fabric.api.event.Event
import net.minecraft.server.MinecraftServer

/**
 * The runtime's handle on the bootstrap (docs/hot-reload.md). Set once by
 * [ConduitRuntimeImpl.start] before any feature registers.
 */
object Conduit {
    lateinit var context: RuntimeContext
        internal set

    /**
     * Runs [action] after lifecycle replay when this runtime is hot-loaded into
     * a live server (never on a cold start) — for features that must reconcile
     * per-player state with players who joined before the reload.
     */
    fun onHotActivate(action: (MinecraftServer) -> Unit) {
        context.events.onHotActivate(java.util.function.Consumer { server -> action(server) })
    }
}

/**
 * The runtime's doorway to Fabric events: `EVENT.reloadable.register { ... }`
 * routes the handler through the bootstrap's severable guards so a hot reload
 * can unload it. Registering on a bare event is forbidden in this module — the
 * handler would outlive the runtime and pin its classloader. (The `register`
 * spelling is kept because Java's SAM conversion only applies to `Event`'s own
 * Java method — a Kotlin `listen(handler: T)` helper cannot accept lambdas.)
 */
val <T : Any> Event<T>.reloadable: Event<T>
    get() = Conduit.context.events.reloadable(this)
