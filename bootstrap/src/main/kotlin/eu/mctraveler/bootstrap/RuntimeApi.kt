package eu.mctraveler.bootstrap

import java.util.function.Consumer
import net.fabricmc.fabric.api.event.Event
import net.minecraft.server.MinecraftServer

/**
 * The contract a runtime jar fulfils. The bootstrap discovers the implementation
 * via [java.util.ServiceLoader] (`META-INF/services/eu.mctraveler.bootstrap.ConduitRuntime`)
 * — from the side-loaded jar in production, from the classpath in dev runs.
 */
interface ConduitRuntime {
    /**
     * Register every feature through [context]. Returns the [MixinHooks]
     * implementation the permanent mixins should route through (null keeps them
     * vanilla-neutral). Runs during mod init on a cold start, or mid-game on a
     * hot reload — feature code must not touch the server here; it sees the
     * server through the events [RuntimeContext.events] replays.
     */
    fun start(context: RuntimeContext): MixinHooks?
}

interface RuntimeContext {
    val events: EventRegistrar

    /** True when this runtime was loaded into an already-running server. */
    val isHotLoad: Boolean
}

/**
 * The only way runtime code may attach to Fabric events. Fabric has no
 * unregistration, so the bootstrap wraps every handler in a severable guard: on
 * unload the guard drops its reference (the handler and its classloader become
 * collectable) and answers all future dispatches with vanilla-neutral defaults.
 *
 * Three event families get replay semantics so a hot reload behaves like a full
 * stop/start:
 *  - `CommandRegistrationCallback.EVENT` handlers are recorded and re-run
 *    against the live dispatcher on reload (and their command roots removed on
 *    unload, with the command tree resent to players).
 *  - `ServerLifecycleEvents.SERVER_STARTING`/`SERVER_STARTED` are replayed to a
 *    hot-loaded runtime; `SERVER_STOPPING`/`SERVER_STOPPED` are replayed to a
 *    runtime being unloaded while the server runs.
 *  - [onHotActivate] actions run last on a hot load, for features that must
 *    reconcile per-player state with players who joined before the reload
 *    (connection JOIN events are deliberately *not* replayed — they carry
 *    player-visible side effects like join announcements).
 */
interface EventRegistrar {
    /**
     * A registering-only view of [event] whose `register` routes through this
     * runtime's severable guards. Returned as an [Event] so call sites keep
     * Java's SAM conversion (`EVENT.reloadable.register { ... }`) — Kotlin
     * cannot SAM-convert a lambda to a bare type variable, so a Kotlin
     * `listen(handler: T)` helper would not compile against lambdas.
     */
    fun <T : Any> reloadable(event: Event<T>): Event<T>

    /** Runs after lifecycle replay when this runtime hot-loads into a live server; never on a cold start. */
    fun onHotActivate(action: Consumer<MinecraftServer>)
}
