package eu.mctraveler.geo

import eu.mctraveler.MCTraveler
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
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

/**
 * The join announcement's country source: asynchronously loads DB-IP data from
 * `<server dir>/mctraveler/geoip/` and publishes it for address lookups.
 */
object GeoIpFeature {
    private const val AUTO_DOWNLOAD_ENV = "CONDUIT_GEOIP_AUTO_DOWNLOAD"
    private const val DOWNLOAD_URL = "https://download.db-ip.com/free/dbip-country-lite-%s.csv.gz"
    private val MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM")

    @Volatile
    private var database: DbIpCountry? = null

    @Volatile
    private var executor: ExecutorService? = null

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            start(server.serverDirectory.resolve("mctraveler").resolve("geoip"))
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
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
            val currentMonth = YearMonth.now(ZoneOffset.UTC)
            val current = directory.resolve(fileName(currentMonth))
            var source = current.takeIf(Files::isRegularFile)
            if (source == null && autoDownloadEnabled()) {
                source = download(directory, currentMonth)
            }
            if (source == null) {
                source = findNewestLocal(directory)
            }
            if (source == null) {
                MCTraveler.LOGGER.warn(
                    "No DB-IP country data found under $directory; country join announcements disabled",
                )
                return
            }
            var loadedSource = source
            val loaded = try {
                DbIpCountry.load(source, MCTraveler.LOGGER::info)
            } catch (failure: Exception) {
                val fallback = findNewestLocal(directory, source)
                if (fallback == null) throw failure
                MCTraveler.LOGGER.warn(
                    "Failed to load DB-IP country data from $source; trying $fallback",
                    failure,
                )
                loadedSource = fallback
                DbIpCountry.load(fallback, MCTraveler.LOGGER::info)
            }
            if (executor !== pool || pool.isShutdown) return
            database = loaded
            MCTraveler.LOGGER.info("DB-IP country data loaded from {}", loadedSource)
        } catch (failure: Exception) {
            MCTraveler.LOGGER.warn(
                "Failed to load DB-IP country data; keeping any existing data",
                failure,
            )
        }
    }

    private fun autoDownloadEnabled(): Boolean {
        val value = System.getenv(AUTO_DOWNLOAD_ENV)?.trim() ?: return true
        return !value.equals("false", ignoreCase = true) && value != "0"
    }

    private fun findNewestLocal(directory: Path, excluded: Path? = null): Path? {
        if (Files.notExists(directory)) return null
        Files.walk(directory).use { paths ->
            return paths.iterator().asSequence()
                .filter(Files::isRegularFile)
                .filter { it != excluded }
                .filter(::isDbIpFile)
                .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
        }
    }

    private fun isDbIpFile(path: Path): Boolean {
        val name = path.fileName.toString().lowercase(Locale.ROOT)
        return name.startsWith("dbip-country-lite-") &&
            (name.endsWith(".csv") || name.endsWith(".csv.gz"))
    }

    private fun fileName(month: YearMonth): String = "dbip-country-lite-${month.format(MONTH_FORMAT)}.csv.gz"

    private fun download(directory: Path, currentMonth: YearMonth): Path? {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
        val current = downloadMonth(client, directory, currentMonth)
        if (current.path != null) return current.path
        if (!current.notFound) return null
        return downloadMonth(client, directory, currentMonth.minusMonths(1)).path
    }

    private fun downloadMonth(
        client: HttpClient,
        directory: Path,
        month: YearMonth,
    ): DownloadAttempt {
        val target = directory.resolve(fileName(month))
        val temporary = Files.createTempFile(directory, "${target.fileName}.", ".tmp")
        try {
            val request = HttpRequest.newBuilder(
                URI.create(DOWNLOAD_URL.format(month.format(MONTH_FORMAT))),
            )
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                if (response.statusCode() !in 200..299) {
                    if (response.statusCode() != 404) {
                        MCTraveler.LOGGER.warn(
                            "DB-IP country download for {} returned HTTP {}",
                            month,
                            response.statusCode(),
                        )
                    }
                    return DownloadAttempt(null, response.statusCode() == 404)
                }
                body.use { input ->
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
                }
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
            MCTraveler.LOGGER.info("Downloaded DB-IP country data for {}", month)
            return DownloadAttempt(target, false)
        } catch (failure: Exception) {
            MCTraveler.LOGGER.warn(
                "Failed to download DB-IP country data for $month",
                failure,
            )
            return DownloadAttempt(null, false)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private data class DownloadAttempt(
        val path: Path?,
        val notFound: Boolean,
    )
}
