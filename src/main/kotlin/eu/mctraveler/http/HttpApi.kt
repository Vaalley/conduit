package eu.mctraveler.http

import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import eu.mctraveler.MCTraveler
import eu.mctraveler.tablist.TabListFeature
import eu.mctraveler.text.Paint
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/**
 * A small authenticated HTTP interface that lets external tools (the Observer Discord bot)
 * read server state and send broadcasts. It runs on the loopback interface only and requires
 * a shared bearer token configured in `CONDUIT_HTTP_TOKEN`.
 */
object HttpApi {
    private const val DEFAULT_PORT = 8080
    private const val REQUEST_TIMEOUT_SECONDS = 5L

    private val gson = Gson()
    private var httpServer: HttpServer? = null
    private var minecraftServer: MinecraftServer? = null

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            minecraftServer = server
            start()
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
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
            server.createContext("/status") { exchange -> handleStatus(exchange, token) }
            server.createContext("/broadcast") { exchange -> handleBroadcast(exchange, token) }
            server.executor = Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "mctraveler-http").apply { isDaemon = true }
            }
            server.start()
            httpServer = server
            MCTraveler.LOGGER.info("HTTP interface listening on $address")
        } catch (failure: Exception) {
            MCTraveler.LOGGER.error("Failed to start HTTP interface on $address", failure)
        }
    }

    private fun stop() {
        try {
            httpServer?.stop(0)
        } catch (failure: Exception) {
            MCTraveler.LOGGER.error("Failed to stop HTTP interface", failure)
        } finally {
            httpServer = null
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
            try {
                val players = server.playerList.players.map { it.gameProfile.name }
                val tps = TabListFeature.tps(server.averageTickTimeNanos)
                val response = StatusResponse(players.size, players, tps)
                future.complete(gson.toJson(response))
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }

        try {
            val json = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            sendResponse(exchange, 200, json, "application/json; charset=UTF-8")
        } catch (_: TimeoutException) {
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

        val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
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
            sendResponse(exchange, 503, "Server did not respond in time")
        } catch (error: Exception) {
            MCTraveler.LOGGER.error("HTTP /broadcast failed", error)
            sendResponse(exchange, 500, "Internal server error")
        }
    }

    private fun broadcastMessage(request: BroadcastRequest): Component {
        val content = checkNotNull(request.content)
        val sender = request.sender?.takeIf(String::isNotBlank)
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

    private data class StatusResponse(
        val online: Int,
        val players: List<String>,
        val tps: Double,
    )

    private data class BroadcastRequest(
        val sender: String?,
        val content: String?,
    )
}
