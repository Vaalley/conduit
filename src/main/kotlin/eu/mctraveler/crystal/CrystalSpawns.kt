package eu.mctraveler.crystal

import eu.mctraveler.worlds.DimensionRole
import eu.mctraveler.worlds.Landing
import eu.mctraveler.worlds.WorldsFeature
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/** The two fixed, free teleportation-crystal spawn landings. */
object CrystalSpawns {

    /** Spawn 1, the original spawn-town landing. */
    fun spawn1(player: ServerPlayer): Landing = landing(player, 16.5, 71.0, -15.5, 180.0f)

    /** Spawn 2, the remote landing beyond spawn town. */
    fun spawn2(player: ServerPlayer): Landing = landing(player, 0.5, 67.5, 802816.5, 0.0f)

    private fun landing(
        player: ServerPlayer,
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
    ): Landing {
        val server = player.level().server
        val primary = WorldsFeature.worlds?.byId("primary")?.dimension(DimensionRole.OVERWORLD)
            ?: Level.OVERWORLD
        val level = server.getLevel(primary) ?: server.overworld()
        return Landing(level, x, y, z, yaw, 0.0f)
    }
}
