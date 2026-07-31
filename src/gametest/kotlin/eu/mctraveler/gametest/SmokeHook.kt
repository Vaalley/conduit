package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.worlds.DimensionRole
import eu.mctraveler.worlds.World
import eu.mctraveler.worlds.WorldsFeature
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Production smoke check (the `prodServer` Gradle task). Only active when the JVM is
 * started with -Dmctraveler.smoke=true: once the real dedicated server has fully
 * started, verify the mod initialized and that the whole World topology is live, then
 * stop the server so the task exits cleanly. Any failure crashes the server, failing
 * the Gradle task with a non-zero exit.
 *
 * The topology check is the one thing only this task can prove. Gametests run on
 * vanilla's `GameTestServer`, which bakes its world against an empty level-stem
 * registry and needs `GameTestServerDatapackDimensionsMixin` to see Secondary at all;
 * a real dedicated server loads the mod jar's datapack dimensions itself. So the
 * assertion that Secondary's trio genuinely ships and loads in production lives here
 * (ticket 01 left the smoke proving only the vanilla trio; ticket 04 shipped Secondary).
 */
object SmokeHook : DedicatedServerModInitializer {
    private val LOGGER = LoggerFactory.getLogger("mctraveler-smoke")

    override fun onInitializeServer() {
        if (System.getProperty("mctraveler.smoke") == null) return

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            check(MCTraveler.initialized) {
                "smoke: server started but MCTraveler did not initialize"
            }
            checkWorlds(server)
            LOGGER.info("MCTraveler prod smoke OK: mod initialized and both Worlds are live; stopping.")
            server.halt(false)
        }
    }

    /**
     * Every World's every dimension, and the out-of-trio embassies dimension,
     * are loaded on this real dedicated server.
     */
    private fun checkWorlds(server: MinecraftServer) {
        val worlds = checkNotNull(WorldsFeature.worlds) {
            "smoke: the Worlds service did not come up with the server"
        }
        val ids = worlds.all.map(World::id)
        check(ids == listOf("primary", "secondary")) {
            "smoke: expected the Primary and Secondary Worlds, found $ids"
        }
        for (world in worlds.all) {
            for (role in DimensionRole.entries) {
                val dimension = world.dimension(role)
                val level = checkNotNull(server.getLevel(dimension)) {
                    "smoke: ${world.id}'s ${role.id} (${dimension.identifier()}) is not loaded"
                }
                LOGGER.info(
                    "MCTraveler prod smoke: {} {} = {}",
                    world.id,
                    role.id,
                    level.dimension().identifier(),
                )
            }
        }
        // Embassies is in no World (ADR 0003), so the loop above cannot reach
        // it — and it is datapack-defined, so only a real boot proves it ships.
        val embassies = checkNotNull(server.getLevel(EmbassiesFeature.DIMENSION)) {
            "smoke: the ${EmbassiesFeature.DIMENSION.identifier()} dimension is not loaded"
        }
        check(worlds.worldOf(embassies.dimension()) == null) {
            "smoke: the embassies dimension was claimed by a World"
        }
        LOGGER.info("MCTraveler prod smoke: embassies = {}", embassies.dimension().identifier())
    }
}
