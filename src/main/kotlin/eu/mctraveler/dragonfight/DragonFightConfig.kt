package eu.mctraveler.dragonfight

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.mctraveler.MCTraveler
import java.nio.file.Files
import java.nio.file.Path
import net.fabricmc.loader.api.FabricLoader

/**
 * The persistent knobs for private dragon fights, with safe defaults for new servers.
 */
object DragonFightConfig {
    const val CONFIG_FILE = "dragonfight.json"

    data class Settings(
        val requiredEndermanKills: Int,
        val requiredBlazeKills: Int,
        val borderRadius: Int,
        val arenaTtlHours: Int,
    ) {
        init {
            require(requiredEndermanKills >= 0)
            require(requiredBlazeKills >= 0)
            require(borderRadius > 0)
            require(arenaTtlHours > 0)
        }

        val arenaTtlMillis: Long get() = arenaTtlHours * 60L * 60L * 1000L
    }

    val DEFAULTS = Settings(10, 10, 300, 168)

    @Volatile
    private var cached: Settings? = null

    fun settings(): Settings = cached ?: reload()

    fun reload(): Settings = load(configFile()).also { cached = it }

    fun load(file: Path): Settings {
        return try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.parent)
                Files.writeString(file, encode(DEFAULTS))
                DEFAULTS
            } else {
                val root = JsonParser.parseString(Files.readString(file)).asJsonObject
                Settings(
                    int(root, "requiredEndermanKills", DEFAULTS.requiredEndermanKills),
                    int(root, "requiredBlazeKills", DEFAULTS.requiredBlazeKills),
                    int(root, "borderRadius", DEFAULTS.borderRadius),
                    int(root, "arenaTtlHours", DEFAULTS.arenaTtlHours),
                )
            }
        } catch (error: Exception) {
            MCTraveler.LOGGER.error("Failed to load dragon fight config {}: {}", file, error.message, error)
            DEFAULTS
        }
    }

    private fun configFile(): Path = FabricLoader.getInstance().gameDir.resolve("mctraveler").resolve(CONFIG_FILE)

    private fun int(root: JsonObject, key: String, fallback: Int): Int =
        root.get(key)?.takeUnless { it.isJsonNull }?.asInt ?: fallback

    private fun encode(settings: Settings): String = GsonBuilder().setPrettyPrinting().create().toJson(
        JsonObject().apply {
            addProperty("requiredEndermanKills", settings.requiredEndermanKills)
            addProperty("requiredBlazeKills", settings.requiredBlazeKills)
            addProperty("borderRadius", settings.borderRadius)
            addProperty("arenaTtlHours", settings.arenaTtlHours)
        },
    )
}
