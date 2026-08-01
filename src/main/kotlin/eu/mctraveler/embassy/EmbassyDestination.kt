package eu.mctraveler.embassy

import com.google.gson.JsonObject
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.worlds.Landing
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * Where an embassy's anchor sends the visitor who steps onto it: the place its
 * owner was standing when they ran `/embassy create`.
 *
 * It is stored as free-form JSON under a region's [KEY] metadata, in the shape
 * Nucleus wrote — a position, a facing, and the *legacy* world string rather
 * than a dimension id ([RegionWorlds]). That string is why this is its own type
 * rather than a [eu.mctraveler.worlds.Waypoint]: it names a world the server may
 * no longer have, so it is not a dimension key until [resolve] has looked it up.
 *
 * This is the only place that knows the shape. Reading it key-by-key at each
 * call site — the anchor, and the Nucleus importer's report of destinations
 * pointing at worlds nobody kept — meant two copies of the same six keys and a
 * chain of `asJsonObject` casts to go with them.
 */
data class EmbassyDestination(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {

    /**
     * Somewhere to go, or null when [world] is a world this server does not
     * have — a destination naming a world nobody kept is simply not a place
     * (which is exactly what the importer warns an operator about).
     */
    fun resolve(server: MinecraftServer): Landing? =
        RegionWorlds.dimensionFor(world)
            ?.let { server.getLevel(it) }
            ?.let { Landing(it, x, y, z, yaw, pitch) }

    /**
     * This destination as the region file carries it.
     *
     * The key order is the order Nucleus wrote, and `x`/`y`/`z` are doubles
     * where `yaw`/`pitch` are floats, because the stored bytes are pinned by
     * test and shared with twenty imported Nucleus-era embassies.
     */
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("x", x)
        addProperty("y", y)
        addProperty("z", z)
        addProperty("yaw", yaw)
        addProperty("pitch", pitch)
        addProperty("world", world)
    }

    companion object {

        /** The metadata key an embassy's anchor reads its destination from. */
        const val KEY = "embassy-destination"

        /**
         * What [region]'s anchor sends visitors to, or null when it has no
         * destination at all.
         *
         * A destination that is present but *malformed* is not guessed at: the
         * numeric reads are strict, so corrupt metadata fails loudly here
         * rather than teleporting someone to a quietly-defaulted zero. The
         * importer refuses such data at the door
         * ([eu.mctraveler.importer.NucleusRegions]), so reaching this from a
         * live region means the file was edited by hand.
         */
        fun of(region: Region): EmbassyDestination? {
            val stored = region.metadata[KEY]?.asJsonObject ?: return null
            val world = stored.get("world")?.asString ?: return null
            return EmbassyDestination(
                world = world,
                x = stored.get("x").asDouble,
                y = stored.get("y").asDouble,
                z = stored.get("z").asDouble,
                yaw = stored.get("yaw").asFloat,
                pitch = stored.get("pitch").asFloat,
            )
        }

        /** Where [player] is standing, as the destination an anchor reads back. */
        fun at(player: ServerPlayer): EmbassyDestination = EmbassyDestination(
            // The legacy world string, so the file reads like Nucleus's did.
            world = RegionWorlds.legacyName(player.level().dimension()),
            x = player.x,
            y = player.y,
            z = player.z,
            yaw = player.yRot,
            pitch = player.xRot,
        )
    }
}
