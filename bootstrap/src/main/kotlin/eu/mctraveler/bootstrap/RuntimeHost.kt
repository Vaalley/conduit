package eu.mctraveler.bootstrap

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.tree.CommandNode
import java.net.URLClassLoader
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.util.ServiceLoader
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Loads, watches, and hot-swaps the runtime jar (docs/hot-reload.md).
 *
 * Production: the runtime jar lives in `<server dir>/mctraveler-runtime/`
 * (override with -Dmctraveler.runtimeDir). A filesystem watcher picks up any
 * jar change, unloads the old runtime completely — synthesized stop lifecycle,
 * command roots removed, every event guard severed, classloader closed — and
 * only then loads the new jar, replaying the start lifecycle and re-registering
 * commands against the live server. Players are told when the swap finishes.
 *
 * Dev runs (runServer, runGameTest): the runtime is on the classpath, so the
 * ServiceLoader finds it on our own classloader and it is loaded directly with
 * no watcher — gametests share its classes, and JBR hotswap covers iteration.
 */
object RuntimeHost {
    private val LOGGER = LoggerFactory.getLogger("mctraveler-bootstrap")

    /** Debounce window: a jar being copied in fires many watch events. */
    private const val QUIET_MILLIS = 500L

    @Volatile
    private var session: Session? = null

    @Volatile
    private var currentServer: MinecraftServer? = null

    // Captured from the permanent command callback on the cold start, reused to
    // replay command registration on hot reloads.
    @Volatile
    private var commandContext: CommandBuildContext? = null

    @Volatile
    private var commandSelection: Commands.CommandSelection? = null

    private lateinit var runtimeDir: Path

    /** Size+mtime of the jar the current session was loaded from; skips no-op watch wakeups. */
    @Volatile
    private var loadedJarFingerprint: Pair<Long, Long>? = null

    /** The one "is the runtime alive" signal permanent code (smoke hook) may check. */
    @JvmStatic
    fun isRuntimeActive(): Boolean = session?.alive == true && Hooks.impl != null

    fun initialize() {
        ServerLifecycleEvents.SERVER_STARTING.register { server -> currentServer = server }
        ServerLifecycleEvents.SERVER_STOPPED.register { currentServer = null }
        // The one permanent command callback: delegates to whatever runtime is
        // loaded. Fabric fires it on server start (and on datapack reloads).
        CommandRegistrationCallback.EVENT.register { dispatcher, context, selection ->
            commandContext = context
            commandSelection = selection
            session?.let { registerSessionCommands(it, dispatcher) }
        }

        val classpathRuntime = ServiceLoader.load(ConduitRuntime::class.java, javaClass.classLoader)
            .findFirst().orElse(null)
        if (classpathRuntime != null) {
            LOGGER.info("runtime found on the classpath (dev run): loading directly, no watcher")
            startSession(Session(loader = null, jarCopy = null), classpathRuntime)
            return
        }

        runtimeDir = System.getProperty("mctraveler.runtimeDir")
            ?.let { Path.of(it) }
            ?: FabricLoader.getInstance().gameDir.resolve("mctraveler-runtime")
        Files.createDirectories(runtimeDir)
        val jar = newestJar()
        if (jar != null) {
            loadFromJar(jar)
        } else {
            LOGGER.warn("no runtime jar in {} — the server runs vanilla-neutral until one appears", runtimeDir)
        }
        startWatcher()
    }

    // ---------------------------------------------------------------- loading

    private fun newestJar(): Path? = runtimeDir.listDirectoryEntries()
        .filter { it.extension == "jar" }
        .maxByOrNull { it.getLastModifiedTime() }

    private fun fingerprint(jar: Path): Pair<Long, Long> =
        Files.size(jar) to jar.getLastModifiedTime().toMillis()

    private fun loadFromJar(jar: Path) {
        // Load from a private copy so the watched file stays free to overwrite
        // while the classloader holds its jar open.
        val copy = Files.createTempFile("mctraveler-runtime-", ".jar")
        Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING)
        val loader = URLClassLoader(arrayOf(copy.toUri().toURL()), javaClass.classLoader)
        // asSequence, not first(): in dev both the classpath provider and the
        // jar's provider are visible through the child loader — only a provider
        // the child itself defined proves the jar is a runtime.
        val entry = ServiceLoader.load(ConduitRuntime::class.java, loader)
            .asSequence()
            .firstOrNull { it.javaClass.classLoader === loader }
        if (entry == null) {
            LOGGER.error(
                "{} declares no eu.mctraveler.bootstrap.ConduitRuntime service; runtime stays unloaded", jar,
            )
            loader.close()
            Files.deleteIfExists(copy)
            return
        }
        loadedJarFingerprint = fingerprint(jar)
        LOGGER.info("loading runtime from {}", jar)
        startSession(Session(loader, copy), entry)
    }

    private fun startSession(session: Session, entry: ConduitRuntime) {
        val server = currentServer
        session.isHotLoad = server != null
        this.session = session
        val context = object : RuntimeContext {
            override val events: EventRegistrar = session
            override val isHotLoad: Boolean = session.isHotLoad
        }
        val hooks = try {
            entry.start(context)
        } catch (e: Exception) {
            LOGGER.error("runtime failed to start; unloading it again", e)
            this.session = null
            session.kill()
            closeLoader(session)
            return
        }
        Hooks.impl = hooks

        if (server != null) {
            // A hot load behaves like a full server start from the runtime's
            // point of view: lifecycle replay, then commands, then the
            // per-player state reconciliation hook.
            session.serverStarting.forEach { safely("SERVER_STARTING replay") { it.onServerStarting(server) } }
            session.serverStarted.forEach { safely("SERVER_STARTED replay") { it.onServerStarted(server) } }
            registerSessionCommands(session, server.commands.dispatcher)
            resendCommandTrees(server)
            session.hotActivate.forEach { safely("hot-activate") { it.accept(server) } }
            announceUpdate(server)
        }
        LOGGER.info("runtime started{}", if (server != null) " (hot)" else "")
    }

    // -------------------------------------------------------------- unloading

    /**
     * The "perfectly unloaded" sweep: synthesized stop lifecycle (features
     * flush state and stop their own threads), command roots removed from the
     * live dispatcher, mixin hooks dropped, every event guard severed, and the
     * runtime's classloader closed.
     */
    private fun unloadCurrent() {
        val session = this.session ?: return
        val server = currentServer
        if (server != null) {
            session.serverStopping.forEach { safely("SERVER_STOPPING replay") { it.onServerStopping(server) } }
            session.serverStopped.forEach { safely("SERVER_STOPPED replay") { it.onServerStopped(server) } }
            removeSessionCommands(session, server.commands.dispatcher)
            resendCommandTrees(server)
        }
        Hooks.impl = null
        this.session = null
        session.kill()
        closeLoader(session)
        loadedJarFingerprint = null
        LOGGER.info("runtime unloaded")
    }

    private fun closeLoader(session: Session) {
        try {
            session.loader?.close()
        } catch (e: Exception) {
            LOGGER.warn("failed to close runtime classloader", e)
        }
        session.jarCopy?.let { Files.deleteIfExists(it) }
    }

    // --------------------------------------------------------------- commands

    private fun registerSessionCommands(session: Session, dispatcher: CommandDispatcher<CommandSourceStack>) {
        val context = commandContext ?: return
        val selection = commandSelection ?: return
        val root = dispatcher.root
        val before = root.children.mapTo(mutableSetOf()) { it.name }
        session.commandRegistrars.forEach { safely("command registration") { it.register(dispatcher, context, selection) } }
        val added = root.children.mapTo(mutableSetOf()) { it.name }.apply { removeAll(before) }
        // Replace, not accumulate: names recorded against a previous dispatcher
        // (datapack reloads rebuild it) are meaningless on this one.
        session.registeredRootCommands.clear()
        session.registeredRootCommands += added
    }

    private fun removeSessionCommands(session: Session, dispatcher: CommandDispatcher<CommandSourceStack>) {
        session.registeredRootCommands.forEach { name -> removeChild(dispatcher.root, name) }
        session.registeredRootCommands.clear()
    }

    /**
     * Brigadier has no public removal, so this reaches into [CommandNode]'s
     * three lookup maps (`children`, `literals`, `arguments`).
     */
    private fun removeChild(root: CommandNode<CommandSourceStack>, name: String) {
        for (fieldName in listOf("children", "literals", "arguments")) {
            val field = CommandNode::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            (field.get(root) as MutableMap<*, *>).remove(name)
        }
    }

    private fun resendCommandTrees(server: MinecraftServer) {
        server.playerList.players.forEach { server.commands.sendCommands(it) }
    }

    // --------------------------------------------------------------- watching

    private fun startWatcher() {
        Thread(::watchLoop, "mctraveler-runtime-watch").apply {
            isDaemon = true
            start()
        }
    }

    private fun watchLoop() {
        val watcher = FileSystems.getDefault().newWatchService()
        runtimeDir.register(
            watcher,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )
        while (true) {
            val key = try {
                watcher.take()
            } catch (_: InterruptedException) {
                return
            }
            var sawJar = key.pollEvents().any { (it.context() as? Path)?.extension == "jar" }
            key.reset()
            // Debounce: drain follow-up events until the directory goes quiet.
            while (true) {
                val more = watcher.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS) ?: break
                sawJar = more.pollEvents().any { (it.context() as? Path)?.extension == "jar" } || sawJar
                more.reset()
            }
            if (!sawJar) continue
            try {
                onJarChanged()
            } catch (e: Exception) {
                LOGGER.error("runtime reload failed", e)
            }
        }
    }

    private fun onJarChanged() {
        val jar = newestJar()
        if (jar != null) {
            awaitStableSize(jar)
            if (fingerprint(jar) == loadedJarFingerprint) return // no-op wakeup
        } else if (session == null) {
            return
        }
        val server = currentServer
        if (server != null) {
            server.execute { swapTo(jar) }
        } else {
            swapTo(jar)
        }
    }

    /** A jar still being copied grows between polls; wait until it settles. */
    private fun awaitStableSize(jar: Path) {
        var last = fingerprint(jar)
        repeat(20) {
            Thread.sleep(QUIET_MILLIS)
            val now = fingerprint(jar)
            if (now == last) return
            last = now
        }
    }

    /** Runs on the server thread (or pre-start init thread): old out, new in. */
    private fun swapTo(jar: Path?) {
        unloadCurrent()
        if (jar == null) {
            LOGGER.warn("runtime jar removed from {}; the server runs vanilla-neutral until one appears", runtimeDir)
            return
        }
        loadFromJar(jar)
    }

    private fun announceUpdate(server: MinecraftServer) {
        val message = Component.literal("A new update has been applied.").withStyle(ChatFormatting.GOLD)
        server.playerList.broadcastSystemMessage(message, false)
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            LOGGER.error("runtime {} failed", what, e)
        }
    }
}
