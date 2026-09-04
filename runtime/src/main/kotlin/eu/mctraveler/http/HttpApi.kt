package eu.mctraveler.http

import com.google.gson.GsonBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import eu.mctraveler.MCTraveler
import eu.mctraveler.chat.ChatBridge
import eu.mctraveler.chat.ChatMessage
import eu.mctraveler.passport.PassportJson
import eu.mctraveler.reloadable
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.tablist.TabListFeature
import eu.mctraveler.text.Paint
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.roundToLong
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/**
 * A small authenticated HTTP interface that lets external tools (the Observer Discord bot)
 * read server state, send broadcasts, and poll recent in-game chat. It runs on the loopback
 * interface only and requires a shared bearer token configured in `CONDUIT_HTTP_TOKEN`.
 */
object HttpApi {
    private const val DEFAULT_PORT = 8080
    private const val REQUEST_TIMEOUT_SECONDS = 5L
    private const val MAX_BODY_BYTES = 4096
    private const val MAX_BROADCAST_LENGTH = 256
    private const val MAX_SENDER_LENGTH = 32

    private val gson = GsonBuilder().serializeNulls().create()
    private val joinedAt = ConcurrentHashMap<UUID, Long>()

    @Volatile
    private var httpServer: HttpServer? = null

    @Volatile
    private var minecraftServer: MinecraftServer? = null

    private var executor: ExecutorService? = null

    fun register() {
        ServerPlayConnectionEvents.JOIN.reloadable.register { handler, _, _ ->
            joinedAt[handler.player.uuid] = System.currentTimeMillis()
        }
        ServerPlayConnectionEvents.DISCONNECT.reloadable.register { handler, _ ->
            joinedAt.remove(handler.player.uuid)
        }
        ServerLifecycleEvents.SERVER_STARTED.reloadable.register { server ->
            minecraftServer = server
            start()
        }
        ServerLifecycleEvents.SERVER_STOPPED.reloadable.register {
            stop()
            minecraftServer = null
        }
    }

    private fun start() {
        val token = System.getenv("CONDUIT_HTTP_TOKEN")
        if (token.isNullOrBlank()) {
            MCTraveler.LOGGER.warn("CONDUIT_HTTP_TOKEN not set; HTTP interface disabled")
            return
        }

        val port = System.getenv("CONDUIT_HTTP_PORT")?.toIntOrNull() ?: DEFAULT_PORT
        val address = InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)

        try {
            val server = HttpServer.create(address, 0)
            val pool = Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "mctraveler-http").apply { isDaemon = true }
            }
            server.createContext("/status") { exchange -> handleStatus(exchange, token) }
            server.createContext("/broadcast") { exchange -> handleBroadcast(exchange, token) }
            server.createContext("/chat") { exchange -> handleChat(exchange, token) }
            server.createContext("/passport") { exchange -> handlePassport(exchange, token) }
            server.createContext("/passports/top") { exchange -> handleTop(exchange, token) }
            server.executor = pool
            server.start()
            httpServer = server
            executor = pool
            MCTraveler.LOGGER.info("HTTP interface listening on $address")
        } catch (failure: Exception) {
            MCTraveler.LOGGER.error("Failed to start HTTP interface on $address", failure)
        }
    }

    private fun stop() {
        val server = httpServer
        httpServer = null
        try {
            server?.stop(0)
        } catch (failure: Exception) {
            MCTraveler.LOGGER.error("Failed to stop HTTP interface", failure)
        }

        val pool = executor
        executor = null
        try {
            pool?.shutdownNow()
        } catch (failure: Exception) {
            MCTraveler.LOGGER.error("Failed to shut down HTTP executor", failure)
        }
    }

    private fun handleStatus(exchange: HttpExchange, token: String) {
        if (exchange.requestMethod != "GET") {
            sendResponse(exchange, 405, "Method not allowed")
            return
        }
        if (!authenticate(exchange, token)) {
            sendResponse(exchange, 401, "Unauthorized")
            return
        }

        val server = minecraftServer ?: run {
            sendResponse(exchange, 503, "Server not ready")
            return
        }

        val future = CompletableFuture<String>()
        server.execute {
            if (future.isCancelled) return@execute
            try {
                // A vanished admin (issue #47) is absent from /status entirely —
                // the count, the name list, and the session detail.
                val online = server.playerList.players
                    .filterNot(eu.mctraveler.vanish.VanishFeature::isVanished)
                val players = online.map { it.gameProfile.name }
                val tps = TabListFeature.tps(server.averageTickTimeNanos)
                val sessions = online.map { player ->
                    PlayerSession(player.gameProfile.name, joinedAt[player.uuid])
                }
                val world = server.overworld()
                val response = StatusResponse(
                    online = players.size,
                    players = players,
                    tps = tps,
                    sessions = sessions,
                    startedAt = ManagementFactory.getRuntimeMXBean().startTime,
                    minecraftVersion = server.serverVersion,
                    modVersion = FabricLoader.getInstance()
                        .getModContainer(MCTraveler.MOD_ID)
                        .map { it.metadata.version.friendlyString }
                        .orElse("unknown"),
                    dayTime = world.getOverworldClockTime() % 24000,
                    raining = world.isRaining,
                    thundering = world.isThundering,
                )
                future.complete(gson.toJson(response))
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }

        try {
            val json = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            sendResponse(exchange, 200, json, "application/json; charset=UTF-8")
        } catch (_: TimeoutException) {
            future.cancel(false)
            sendResponse(exchange, 503, "Server did not respond in time")
        } catch (_: CancellationException) {
            sendResponse(exchange, 503, "Server did not respond in time")
        } catch (error: Exception) {
            MCTraveler.LOGGER.error("HTTP /status failed", error)
            sendResponse(exchange, 500, "Internal server error")
        }
    }

    private fun handleBroadcast(exchange: HttpExchange, token: String) {
        if (exchange.requestMethod != "POST") {
            sendResponse(exchange, 405, "Method not allowed")
            return
        }
        if (!authenticate(exchange, token)) {
            sendResponse(exchange, 401, "Unauthorized")
            return
        }

        val server = minecraftServer ?: run {
            sendResponse(exchange, 503, "Server not ready")
            return
        }

        val body = readBody(exchange)
        if (body == null) {
            sendResponse(exchange, 413, "Request body too large")
            return
        }

        val request = try {
            gson.fromJson(body, BroadcastRequest::class.java)
        } catch (_: Exception) {
            sendResponse(exchange, 400, "Invalid JSON body")
            return
        }

        if (request?.content.isNullOrBlank()) {
            sendResponse(exchange, 400, "Missing or empty 'content'")
            return
        }

        val future = CompletableFuture<Void>()
        server.execute {
            if (future.isCancelled) return@execute
            try {
                val message = broadcastMessage(request)
                server.playerList.broadcastSystemMessage(message, false)
                future.complete(null)
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }

        try {
            future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            sendResponse(exchange, 204, "")
        } catch (_: TimeoutException) {
            future.cancel(false)
            sendResponse(exchange, 503, "Server did not respond in time")
        } catch (_: CancellationException) {
            sendResponse(exchange, 503, "Server did not respond in time")
        } catch (error: Exception) {
            MCTraveler.LOGGER.error("HTTP /broadcast failed", error)
            sendResponse(exchange, 500, "Internal server error")
        }
    }

    private fun readBody(exchange: HttpExchange): String? {
        val bytes = try {
            exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        } catch (failure: Exception) {
            MCTraveler.LOGGER.warn("Failed to read request body", failure)
            return null
        }
        if (bytes.size > MAX_BODY_BYTES) {
            return null
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun broadcastMessage(request: BroadcastRequest): Component {
        val content = sanitize(checkNotNull(request.content), MAX_BROADCAST_LENGTH)
        val sender = request.sender?.let { sanitize(it, MAX_SENDER_LENGTH) }?.takeIf(String::isNotBlank)
        if (sender == null) {
            return Component.literal(content)
        }
        return Paint.gray(
            "[Discord] ",
            Paint.green(sender),
            ": ",
            Paint.white(content),
        )
    }

    private fun sanitize(text: String, maxLength: Int): String {
        return text
            .replace(Regex("[\r\n]"), " ")
            .replace("§", "")
            .take(maxLength)
            .trim()
    }

    private fun authenticate(exchange: HttpExchange, token: String): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
        if (!header.startsWith("Bearer ")) return false
        val provided = header.substring(7)
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8),
        )
    }

    private fun sendResponse(
        exchange: HttpExchange,
        code: Int,
        body: String,
        contentType: String = "text/plain; charset=UTF-8",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun handleChat(exchange: HttpExchange, token: String) {
        if (exchange.requestMethod != "GET") {
            sendResponse(exchange, 405, "Method not allowed")
            return
        }
        if (!authenticate(exchange, token)) {
            sendResponse(exchange, 401, "Unauthorized")
            return
        }

        val since = exchange.requestURI.query
            ?.split("&")
            ?.asSequence()
            ?.map { it.split("=", limit = 2) }
            ?.find { it.size == 2 && it[0] == "since" }
            ?.get(1)
            ?.toLongOrNull() ?: 0L

        val messages = ChatBridge.poll(since)
        sendResponse(exchange, 200, gson.toJson(ChatResponse(messages)), "application/json; charset=UTF-8")
    }

    private fun handlePassport(exchange: HttpExchange, token: String) {
        if (exchange.requestMethod != "GET") {
            sendResponse(exchange, 405, "Method not allowed")
            return
        }
        if (!authenticate(exchange, token)) {
            sendResponse(exchange, 401, "Unauthorized")
            return
        }
        val name = queryParameter(exchange.requestURI.query, "name")
        if (name.isNullOrBlank()) {
            sendResponse(exchange, 400, "Missing name")
            return
        }
        val server = minecraftServer ?: run {
            sendResponse(exchange, 503, "Server not ready")
            return
        }
        val future = CompletableFuture<String>()
        server.execute {
            if (future.isCancelled) return@execute
            try {
                val persistence = MCTraveler.persistence ?: throw IllegalStateException("persistence is not ready")
                val uuid = server.playerList.getPlayerByName(name)?.uuid ?: persistence.names.uuidFor(name)
                val passport = uuid?.let(persistence.passports::get)
                if (uuid == null || passport == null) {
                    future.complete("__NOT_FOUND__")
                    return@execute
                }
                val regionService = RegionsFeature.requireService()
                val rank = rankFor(uuid, regionService)
                val displayName = persistence.names.usernameFor(uuid) ?: name
                val summary = PassportJson.summary(
                    uuid,
                    displayName,
                    passport,
                    regionService,
                    persistence.names::usernameFor,
                    rank,
                )
                future.complete(gson.toJson(summary))
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        awaitJson(exchange, future, "HTTP /passport failed")
    }

    private fun handleTop(exchange: HttpExchange, token: String) {
        if (exchange.requestMethod != "GET") {
            sendResponse(exchange, 405, "Method not allowed")
            return
        }
        if (!authenticate(exchange, token)) {
            sendResponse(exchange, 401, "Unauthorized")
            return
        }
        val by = queryParameter(exchange.requestURI.query, "by") ?: run {
            sendResponse(exchange, 400, "Missing 'by'")
            return
        }
        if (by !in setOf("distance", "biomes", "embassies")) {
            sendResponse(exchange, 400, "Bad 'by'")
            return
        }
        val limit = (queryParameter(exchange.requestURI.query, "limit")?.toIntOrNull() ?: 10).coerceIn(1, 50)
        val server = minecraftServer ?: run {
            sendResponse(exchange, 503, "Server not ready")
            return
        }
        val future = CompletableFuture<String>()
        server.execute {
            if (future.isCancelled) return@execute
            try {
                val persistence = MCTraveler.persistence ?: throw IllegalStateException("persistence is not ready")
                val regionService = RegionsFeature.requireService()
                val entries = persistence.passports.all()
                    .mapNotNull { (uuid, passport) ->
                        val name = persistence.names.usernameFor(uuid) ?: return@mapNotNull null
                        val value = when (by) {
                            "distance" -> passport.distance.total.roundToLong()
                            "biomes" -> passport.biomes.size.toLong()
                            else -> PassportJson.embassyCount(passport, regionService).toLong()
                        }
                        name to value
                    }
                    .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first.lowercase() })
                    .take(limit)
                    .map { TopEntry(it.first, it.second) }
                future.complete(gson.toJson(TopResponse(by, entries)))
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        awaitJson(exchange, future, "HTTP /passports/top failed")
    }

    private fun awaitJson(exchange: HttpExchange, future: CompletableFuture<String>, label: String) {
        try {
            val json = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (json == "__NOT_FOUND__") sendResponse(exchange, 404, "Unknown player")
            else sendResponse(exchange, 200, json, "application/json; charset=UTF-8")
        } catch (_: TimeoutException) {
            future.cancel(false)
            sendResponse(exchange, 503, "Server did not respond in time")
        } catch (_: CancellationException) {
            sendResponse(exchange, 503, "Server did not respond in time")
        } catch (error: Exception) {
            MCTraveler.LOGGER.error(label, error)
            sendResponse(exchange, 500, "Internal server error")
        }
    }

    private fun rankFor(uuid: UUID, regions: eu.mctraveler.region.RegionService): PassportJson.Rank {
        val passports = MCTraveler.persistence?.passports?.all().orEmpty()
        val target = passports.firstOrNull { it.first == uuid }?.second ?: return PassportJson.Rank(1, 1, 1)
        fun rank(value: (eu.mctraveler.passport.Passport) -> Long): Int =
            1 + passports.count { value(it.second) > value(target) }
        return PassportJson.Rank(
            distance = rank { it.distance.total.roundToLong() },
            biomes = rank { it.biomes.size.toLong() },
            embassies = rank { PassportJson.embassyCount(it, regions).toLong() },
        )
    }

    private fun queryParameter(query: String?, name: String): String? =
        query?.split("&")?.asSequence()
            ?.map { it.split("=", limit = 2) }
            ?.firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }

    private data class ChatResponse(
        val messages: List<ChatMessage>,
    )

    private data class StatusResponse(
        val online: Int,
        val players: List<String>,
        val tps: Double,
        val sessions: List<PlayerSession>,
        val startedAt: Long,
        val minecraftVersion: String,
        val modVersion: String,
        val dayTime: Long,
        val raining: Boolean,
        val thundering: Boolean,
    )

    private data class PlayerSession(
        val name: String,
        val joinedAt: Long?,
    )

    private data class BroadcastRequest(
        val sender: String?,
        val content: String?,
    )

    private data class TopResponse(
        val by: String,
        val entries: List<TopEntry>,
    )

    private data class TopEntry(
        val name: String,
        val value: Long,
    )
}
