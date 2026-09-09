package eu.mctraveler.rtp

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.mctraveler.MCTraveler
import java.nio.file.Files
import java.nio.file.Path
import net.fabricmc.loader.api.FabricLoader

/**
 * The knobs of `/rtp`, read from `<server>/mctraveler/rtp.json` and written
 * there with [DEFAULTS] when the file is absent, the way the crystal spawns are.
 */
object RtpConfig {

    const val CONFIG_FILE = "rtp.json"

    data class Settings(
        /** Farthest a destination may be from the overworld spawn, in blocks. */
        val radius: Int,
        /** Nearest a destination may be to the overworld spawn, in blocks. */
        val minDistance: Int,
        /** How long a player waits between random teleports. */
        val cooldownSeconds: Int,
        /** Farthest a destination may be from the centre of the End. */
        val endRadius: Int = 2_000_000,
        /** Nearest a destination may be from the centre of the End. */
        val endMinDistance: Int = 200_000,
        /** How long a player waits between End teleports. */
        val endCooldownSeconds: Int = 1_800,
    ) {
        init {
            require(radius > 0) { "radius must be positive" }
            require(minDistance in 0 until radius) { "minDistance must be between 0 and radius" }
            require(cooldownSeconds >= 0) { "cooldownSeconds must not be negative" }
            require(endRadius > 0) { "endRadius must be positive" }
            require(endMinDistance in 0 until endRadius) { "endMinDistance must be between 0 and endRadius" }
            require(endCooldownSeconds >= 0) { "endCooldownSeconds must not be negative" }
        }

        val cooldownTicks: Int get() = cooldownSeconds * 20
        val endCooldownTicks: Int get() = endCooldownSeconds * 20
        val overworldRing: RtpPicker.Ring get() = RtpPicker.Ring(minDistance, radius)
        val endRing: RtpPicker.Ring get() = RtpPicker.Ring(endMinDistance, endRadius)
    }

    val DEFAULTS = Settings(radius = 25_000, minDistance = 1_000, cooldownSeconds = 5 * 60)

    @Volatile
    private var cached: Settings? = null

    fun settings(): Settings = cached ?: reload()

    /** Re-reads the file; runs at command registration, so `/reload` picks up edits. */
    fun reload(): Settings = load(configFile()).also { cached = it }

    private fun configFile(): Path =
        FabricLoader.getInstance().getGameDir().resolve("mctraveler").resolve(CONFIG_FILE)

    fun load(file: Path): Settings {
        return try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.parent)
                Files.writeString(file, encode(DEFAULTS))
                DEFAULTS
            } else {
                val root = JsonParser.parseString(Files.readString(file)).asJsonObject
                Settings(
                    radius = int(root, "radius", DEFAULTS.radius),
                    minDistance = int(root, "minDistance", DEFAULTS.minDistance),
                    cooldownSeconds = int(root, "cooldownSeconds", DEFAULTS.cooldownSeconds),
                    endRadius = int(root, "endRadius", DEFAULTS.endRadius),
                    endMinDistance = int(root, "endMinDistance", DEFAULTS.endMinDistance),
                    endCooldownSeconds = int(root, "endCooldownSeconds", DEFAULTS.endCooldownSeconds),
                )
            }
        } catch (error: Exception) {
            MCTraveler.LOGGER.error("Failed to load rtp config {}: {}", file, error.message, error)
            DEFAULTS
        }
    }

    private fun int(root: JsonObject, key: String, fallback: Int): Int =
        root.get(key)?.takeUnless { it.isJsonNull }?.asInt ?: fallback

    private fun encode(settings: Settings): String =
        GsonBuilder().setPrettyPrinting().create().toJson(
            JsonObject().apply {
                addProperty("radius", settings.radius)
                addProperty("minDistance", settings.minDistance)
                addProperty("cooldownSeconds", settings.cooldownSeconds)
                addProperty("endRadius", settings.endRadius)
                addProperty("endMinDistance", settings.endMinDistance)
                addProperty("endCooldownSeconds", settings.endCooldownSeconds)
            },
        )
}
