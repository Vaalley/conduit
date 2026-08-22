package eu.mctraveler.geo

import eu.mctraveler.Conduit
import eu.mctraveler.MCTraveler
import eu.mctraveler.reloadable
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

object GeoIpFeature {
    private const val MAX_AGE_DAYS = 7L
    private const val ZIP_NAME = "GeoLite2-Country-CSV.zip"
    private const val DOWNLOAD_URL =
        "https://download.maxmind.com/geoip/databases/GeoLite2-Country-CSV/download?suffix=zip"
    private const val IPV4_NAME = GeoLite2Country.IPV4_FILE
    private const val IPV6_NAME = GeoLite2Country.IPV6_FILE
    private const val LOCATIONS_NAME = GeoLite2Country.LOCATIONS_FILE

    @Volatile
    private var database: GeoLite2Country? = null

    @Volatile
    private var executor: ExecutorService? = null

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.reloadable.register { server ->
            start(server.serverDirectory.resolve("mctraveler").resolve("geoip"))
        }
        Conduit.onHotActivate { server ->
            start(server.serverDirectory.resolve("mctraveler").resolve("geoip"))
        }
        ServerLifecycleEvents.SERVER_STOPPED.reloadable.register {
            stop()
        }
    }

    fun lookup(address: InetAddress): GeoCountry? = database?.lookup(address)

    private fun start(directory: Path) {
        stop()
        val pool = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mctraveler-geoip").apply { isDaemon = true }
        }
        executor = pool
        pool.execute {
            load(directory, pool)
        }
    }

    private fun stop() {
        database = null
        executor?.shutdownNow()
        executor = null
    }

    private fun load(directory: Path, pool: ExecutorService) {
        try {
            Files.createDirectories(directory)
            var source = findSource(directory)
            val accountId = System.getenv("CONDUIT_MAXMIND_ACCOUNT_ID")
            val licenseKey = System.getenv("CONDUIT_MAXMIND_LICENSE_KEY")
            if (isExpired(source) && !accountId.isNullOrBlank() && !licenseKey.isNullOrBlank()) {
                val downloaded = download(directory, accountId, licenseKey)
                if (downloaded != null) source = DataSource.Zip(downloaded)
            }
            if (source == null) {
                MCTraveler.LOGGER.warn(
                    "No GeoLite2 Country CSV data found under $directory; " +
                        "country join announcements disabled",
                )
                return
            }
            val loaded = source.load { summary ->
                MCTraveler.LOGGER.warn(summary)
            }
            if (executor !== pool || pool.isShutdown) return
            database = loaded
            MCTraveler.LOGGER.info("GeoLite2 Country data loaded from {}", source.description)
        } catch (failure: Exception) {
            MCTraveler.LOGGER.warn(
                "Failed to load GeoLite2 Country data; keeping any existing data",
                failure,
            )
        }
    }

    private fun findSource(directory: Path): DataSource? {
        if (Files.notExists(directory)) return null
        val extracted = HashMap<Path, ExtractedPaths>()
        val zipPaths = ArrayList<Path>()
        Files.walk(directory).use { paths ->
            paths.filter(Files::isRegularFile).forEach { path ->
                when (path.fileName.toString()) {
                    IPV4_NAME -> {
                        extracted.getOrPut(path.parent) { ExtractedPaths() }.ipv4 = path
                    }

                    IPV6_NAME -> {
                        extracted.getOrPut(path.parent) { ExtractedPaths() }.ipv6 = path
                    }

                    LOCATIONS_NAME -> {
                        extracted.getOrPut(path.parent) { ExtractedPaths() }.locations = path
                    }

                    else -> if (
                        path.fileName.toString().contains("Country-CSV", ignoreCase = true) &&
                        path.fileName.toString().endsWith(".zip", ignoreCase = true)
                    ) {
                        zipPaths.add(path)
                    }
                }
            }
        }
        val candidates = ArrayList<DataSource>()
        extracted.values.mapNotNullTo(candidates) { paths ->
            val ipv4 = paths.ipv4
            val ipv6 = paths.ipv6
            val locations = paths.locations
            if (ipv4 != null && ipv6 != null && locations != null) {
                DataSource.Extracted(ipv4, ipv6, locations)
            } else {
                null
            }
        }
        zipPaths.mapTo(candidates, DataSource::Zip)
        return candidates.maxByOrNull { it.modifiedAt() }
    }

    private fun isExpired(source: DataSource?): Boolean {
        val modifiedAt = source?.modifiedAt() ?: return true
        return System.currentTimeMillis() - modifiedAt >= Duration.ofDays(MAX_AGE_DAYS).toMillis()
    }

    private fun download(directory: Path, accountId: String, licenseKey: String): Path? {
        val target = directory.resolve(ZIP_NAME)
        val temporary = Files.createTempFile(directory, "$ZIP_NAME.", ".tmp")
        try {
            val credentials = Base64.getEncoder()
                .encodeToString("$accountId:$licenseKey".toByteArray(Charsets.UTF_8))
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20))
                .build()
            var uri = URI.create(DOWNLOAD_URL)
            var authorization = true
            val visited = HashSet<URI>()
            var downloaded = false
            for (attempt in 0..2) {
                if (!visited.add(uri)) {
                    MCTraveler.LOGGER.warn("MaxMind GeoLite2 download returned a redirect loop")
                    return null
                }
                val request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(2))
                    // The presigned redirect target does not expect MaxMind Basic Auth.
                    .apply {
                        if (authorization) header("Authorization", "Basic $credentials")
                    }
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                var redirect: URI? = null
                response.body().use { body ->
                    when {
                        response.statusCode() in 300..399 -> {
                            val location = response.headers().firstValue("Location").orElse(null)
                            if (location.isNullOrBlank()) {
                                MCTraveler.LOGGER.warn(
                                    "MaxMind GeoLite2 download redirect has no Location header",
                                )
                                return null
                            }
                            redirect = uri.resolve(location)
                        }

                        response.statusCode() !in 200..299 -> {
                            MCTraveler.LOGGER.warn(
                                "MaxMind GeoLite2 download returned HTTP {}",
                                response.statusCode(),
                            )
                            return null
                        }

                        else -> {
                            BufferedInputStream(body).use { input ->
                                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
                            }
                            downloaded = true
                        }
                    }
                }
                if (downloaded) break
                uri = redirect ?: return null
                authorization = false
            }
            if (!downloaded) {
                MCTraveler.LOGGER.warn("MaxMind GeoLite2 download followed too many redirects")
                return null
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            MCTraveler.LOGGER.info("Downloaded fresh GeoLite2 Country data")
            return target
        } catch (failure: Exception) {
            MCTraveler.LOGGER.warn(
                "Failed to download GeoLite2 Country data; keeping any existing local data",
                failure,
            )
            return null
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private sealed interface DataSource {
        val description: String

        fun load(log: (String) -> Unit): GeoLite2Country

        fun modifiedAt(): Long

        data class Extracted(
            val ipv4: Path,
            val ipv6: Path,
            val locations: Path,
        ) : DataSource {
            override val description: String get() = ipv4.parent.toString()

            override fun load(log: (String) -> Unit): GeoLite2Country =
                Files.newBufferedReader(ipv4).use { ipv4Reader ->
                    Files.newBufferedReader(ipv6).use { ipv6Reader ->
                        Files.newBufferedReader(locations).use { locationsReader ->
                            GeoLite2Country.load(ipv4Reader, ipv6Reader, locationsReader, log)
                        }
                    }
                }

            override fun modifiedAt(): Long = listOf(ipv4, ipv6, locations)
                .maxOf { Files.getLastModifiedTime(it).toMillis() }
        }

        data class Zip(val path: Path) : DataSource {
            override val description: String get() = path.toString()

            override fun load(log: (String) -> Unit): GeoLite2Country = GeoLite2Country.loadZip(path, log)

            override fun modifiedAt(): Long = Files.getLastModifiedTime(path).toMillis()
        }
    }

    private data class ExtractedPaths(
        var ipv4: Path? = null,
        var ipv6: Path? = null,
        var locations: Path? = null,
    )
}
