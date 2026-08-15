package eu.mctraveler.bootstrap

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionResult
import org.slf4j.LoggerFactory

/**
 * One loaded runtime: its classloader, every event handler it registered, and
 * the command roots it added. Killing a session severs every guard reference in
 * one sweep, leaving only tiny bootstrap-owned proxy shells behind in Fabric's
 * event arrays — the runtime's classes and classloader become collectable, and
 * a dead proxy answers all future dispatches with vanilla-neutral defaults.
 */
internal class Session(
    /** Null when the runtime was loaded from the classpath (dev runs). */
    val loader: URLClassLoader?,
    /** The temp copy of the jar backing [loader], deleted after unload. */
    val jarCopy: Path?,
) : EventRegistrar {

    @Volatile
    var alive: Boolean = true
        private set

    private val guards = CopyOnWriteArrayList<AtomicReference<Any?>>()

    // Replayed families (see EventRegistrar docs). CopyOnWrite: registration
    // happens on the init/server thread, replay on the server thread.
    val commandRegistrars = CopyOnWriteArrayList<CommandRegistrationCallback>()
    val serverStarting = CopyOnWriteArrayList<ServerLifecycleEvents.ServerStarting>()
    val serverStarted = CopyOnWriteArrayList<ServerLifecycleEvents.ServerStarted>()
    val serverStopping = CopyOnWriteArrayList<ServerLifecycleEvents.ServerStopping>()
    val serverStopped = CopyOnWriteArrayList<ServerLifecycleEvents.ServerStopped>()
    val hotActivate = CopyOnWriteArrayList<Consumer<MinecraftServer>>()

    /** Root literal names this session added to the live command dispatcher. */
    val registeredRootCommands = mutableSetOf<String>()

    var isHotLoad: Boolean = false

    override fun <T : Any> reloadable(event: Event<T>): Event<T> = object : Event<T>() {
        override fun register(listener: T) {
            this@Session.register(event, listener)
        }
    }

    private fun <T : Any> register(event: Event<T>, handler: T) {
        check(alive) { "runtime session already unloaded; cannot register ${handler.javaClass.name}" }
        when {
            // Commands are replayed against the live dispatcher on every
            // (re)load, so the raw handler is recorded instead of registered —
            // RuntimeHost's one permanent callback covers the cold-start firing.
            event === CommandRegistrationCallback.EVENT ->
                commandRegistrars += handler as CommandRegistrationCallback

            else -> {
                when (event) {
                    ServerLifecycleEvents.SERVER_STARTING -> serverStarting += handler as ServerLifecycleEvents.ServerStarting
                    ServerLifecycleEvents.SERVER_STARTED -> serverStarted += handler as ServerLifecycleEvents.ServerStarted
                    ServerLifecycleEvents.SERVER_STOPPING -> serverStopping += handler as ServerLifecycleEvents.ServerStopping
                    ServerLifecycleEvents.SERVER_STOPPED -> serverStopped += handler as ServerLifecycleEvents.ServerStopped
                }
                event.register(guarded(handler))
            }
        }
    }

    override fun onHotActivate(action: Consumer<MinecraftServer>) {
        hotActivate += action
    }

    /**
     * Wraps [handler] in a [Proxy] whose class belongs to the *bootstrap*
     * classloader — the one object Fabric's event array will hold forever must
     * not pin the runtime's classloader. The proxy implements every interface
     * of the handler's class (a SAM-converted lambda implements exactly the
     * callback interface), so the unchecked cast back to T is sound.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> guarded(handler: T): T {
        val interfaces = handler.javaClass.interfaces
        check(interfaces.isNotEmpty()) { "cannot guard ${handler.javaClass.name}: it implements no interface" }
        val ref = AtomicReference<Any?>(handler)
        guards += ref
        val proxy = Proxy.newProxyInstance(javaClass.classLoader, interfaces) { proxyObj, method, args ->
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "hashCode" -> System.identityHashCode(proxyObj)
                    "equals" -> proxyObj === args!![0]
                    else -> "GuardedHandler(${interfaces.first().name})"
                }
                else -> {
                    val h = ref.get()
                    if (h != null) {
                        try {
                            method.invoke(h, *(args ?: EMPTY_ARGS))
                        } catch (e: InvocationTargetException) {
                            throw e.cause ?: e
                        }
                    } else {
                        neutralResult(method)
                    }
                }
            }
        }
        return proxy as T
    }

    /**
     * Severs every handler reference. The proxies stay registered (Fabric has
     * no unregistration) but from here on answer with [neutralResult].
     */
    fun kill() {
        alive = false
        guards.forEach { it.set(null) }
        commandRegistrars.clear()
        serverStarting.clear()
        serverStarted.clear()
        serverStopping.clear()
        serverStopped.clear()
        hotActivate.clear()
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("mctraveler-bootstrap")
        private val EMPTY_ARGS = emptyArray<Any?>()

        /**
         * What a dead handler answers. Fabric's conventions make this uniform:
         * boolean results mean "allow/continue" (true) and interaction results
         * mean "didn't handle it" (PASS) — i.e. vanilla behaviour.
         */
        private fun neutralResult(method: Method): Any? {
            val returnType = method.returnType
            return when {
                returnType == Void.TYPE -> null
                returnType == java.lang.Boolean.TYPE || returnType == java.lang.Boolean::class.java -> true
                InteractionResult::class.java.isAssignableFrom(returnType) -> InteractionResult.PASS
                returnType.isPrimitive -> {
                    LOGGER.warn("dead event handler for {} has no neutral default; returning 0", method)
                    0
                }
                else -> null
            }
        }
    }
}
