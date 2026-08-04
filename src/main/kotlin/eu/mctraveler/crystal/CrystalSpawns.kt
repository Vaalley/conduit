package eu.mctraveler.crystal

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.mctraveler.MCTraveler
import eu.mctraveler.worlds.DimensionRole
import eu.mctraveler.worlds.Landing
import eu.mctraveler.worlds.WorldsFeature
import java.nio.file.Files
import java.nio.file.Path
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/**
 * The configured, free crystal spawn destinations.
 *
 * The live file is `<server>/mctraveler/spawns.json`. It is created with
 * [DEFAULTS] when absent, so an installation has useful spawns without any
 * setup and can add future entries without a code change.
 */
object CrystalSpawns {

    const val CONFIG_FILE = "spawns.json"

    data class Definition(
        val name: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
    )

    val DEFAULTS = listOf(
        Definition("spawn 1", 16.5, 71.0, -15.5, 180.0f),
        Definition("spawn 2", 0.5, 67.5, 802816.5, 0.0f),
    )

    /**
     * The active spawn definitions, in menu and command order.
     *
     * The file is read on every access because command registration can happen
     * before server startup, and game-test servers may reuse this JVM with
     * different config contents.
     */
    fun definitions(): List<Definition> = load(configFile())

    private fun configFile(): Path =
        FabricLoader.getInstance().getGameDir().resolve("mctraveler").resolve(CONFIG_FILE)

    /** The public command name assigned to the zero-based [index]. */
    fun commandName(index: Int): String = "spawn${index + 1}"

    /** Resolves [definition] in the primary overworld, with the existing fallback. */
    fun landing(player: ServerPlayer, definition: Definition): Landing {
        val server = player.level().server
        val primary = WorldsFeature.worlds?.byId("primary")?.dimension(DimensionRole.OVERWORLD)
            ?: Level.OVERWORLD
        val level = server.getLevel(primary) ?: server.overworld()
        return Landing(level, definition.x, definition.y, definition.z, definition.yaw, 0.0f)
    }

    /**
     * Reads a config file, writing [DEFAULTS] when it does not exist. This is
     * public so the persistence format has a small, direct unit-test seam.
     */
    fun load(file: Path): List<Definition> {
        return try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.parent)
                Files.writeString(file, encode(DEFAULTS))
                DEFAULTS
            } else {
                val root = JsonParser.parseString(Files.readString(file)).asJsonObject
                val entries = root.get("spawns")?.asJsonArray
                    ?: throw IllegalArgumentException("spawn config is missing \"spawns\"")
                require(entries.size() > 0) { "spawn config must contain at least one spawn" }
                entries.mapIndexed { index, value ->
                    val spawn = value.asJsonObject
                    Definition(
                        name = string(spawn, "name", index),
                        x = number(spawn, "x", index),
                        y = number(spawn, "y", index),
                        z = number(spawn, "z", index),
                        yaw = number(spawn, "yaw", index).toFloat(),
                    )
                }
            }
        } catch (error: Exception) {
            MCTraveler.LOGGER.error("Failed to load crystal spawn config {}: {}", file, error.message, error)
            DEFAULTS
        }
    }

    private fun string(spawn: JsonObject, key: String, index: Int): String =
        spawn.get(key)?.takeUnless { it.isJsonNull }?.asString
            ?: throw IllegalArgumentException("spawn $index is missing \"$key\"")

    private fun number(spawn: JsonObject, key: String, index: Int): Double =
        spawn.get(key)?.takeUnless { it.isJsonNull }?.asDouble
            ?: throw IllegalArgumentException("spawn $index is missing \"$key\"")

    private fun encode(entries: List<Definition>): String =
        GsonBuilder().setPrettyPrinting().create().toJson(
            JsonObject().apply {
                add(
                    "spawns",
                    JsonArray().apply {
                        entries.forEach { spawn ->
                            add(
                                JsonObject().apply {
                                    addProperty("name", spawn.name)
                                    addProperty("x", spawn.x)
                                    addProperty("y", spawn.y)
                                    addProperty("z", spawn.z)
                                    addProperty("yaw", spawn.yaw)
                                },
                            )
                        }
                    },
                )
            },
        )
}
