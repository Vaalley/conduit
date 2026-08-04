package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.embassy.EmbassiesFeature
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory

/**
 * Production smoke check (the `prodServer` Gradle task). Only active when the JVM is
 * started with -Dmctraveler.smoke=true: once the real dedicated server has fully
 * started, verify the mod initialized and that the dimensions the server is supposed
 * to have are exactly the ones it does have, then stop the server so the task exits
 * cleanly. Any failure crashes the server, failing the Gradle task with a non-zero
 * exit.
 *
 * The dimension check is the one thing only this task can prove. Gametests run on
 * vanilla's `GameTestServer`, which bakes its world against an empty level-stem
 * registry and needs `GameTestServerDatapackDimensionsMixin` to see datapack
 * dimensions at all; a real dedicated server loads the mod jar's own. So the
 * assertion that the Embassies genuinely ships and loads in production lives here —
 * and so, now, does its mirror image.
 *
 * **The absence check is the point of this ticket.** Secondary's trio used to ship in
 * this jar and used to be asserted here as live. The merge relocated its chunk data
 * into Primary's dimensions and the datapack resources were removed, so a server that
 * still creates `mctraveler:secondary` is a server that has quietly resurrected an
 * empty second map — and the failure mode is silent, because an empty dimension looks
 * exactly like one nobody has visited yet. A regression that puts those resources back
 * fails the build here (merge spec, User Story 54).
 */
object SmokeHook : DedicatedServerModInitializer {
    private val LOGGER = LoggerFactory.getLogger("mctraveler-smoke")

    /**
     * The dimensions the retired Worlds subsystem used to create. Named rather
     * than derived, because there is deliberately nothing left in the mod to
     * derive them from — that is what is being asserted.
     */
    private val RETIRED_DIMENSIONS: List<ResourceKey<Level>> =
        listOf("secondary", "secondary_nether", "secondary_end").map(::modDimension)

    override fun onInitializeServer() {
        if (System.getProperty("mctraveler.smoke") == null) return

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            check(MCTraveler.initialized) {
                "smoke: server started but MCTraveler did not initialize"
            }
            checkDimensions(server)
            LOGGER.info("MCTraveler prod smoke OK: mod initialized and every dimension is as expected; stopping.")
            server.halt(false)
        }
    }

    /**
     * The vanilla trio and the out-of-trio embassies dimension (ADR 0003) are
     * loaded on this real dedicated server, and nothing of Secondary's is.
     */
    private fun checkDimensions(server: MinecraftServer) {
        for (dimension in listOf(Level.OVERWORLD, Level.NETHER, Level.END, EmbassiesFeature.DIMENSION)) {
            val level = checkNotNull(server.getLevel(dimension)) {
                "smoke: ${dimension.identifier()} is not loaded"
            }
            LOGGER.info("MCTraveler prod smoke: {} is live", level.dimension().identifier())
        }
        for (dimension in RETIRED_DIMENSIONS) {
            check(server.getLevel(dimension) == null) {
                "smoke: ${dimension.identifier()} exists on this server — the Worlds subsystem was retired " +
                    "and its dimension resources removed, so something has put them back"
            }
        }
        LOGGER.info("MCTraveler prod smoke: none of Secondary's dimensions exist, as expected")
    }

    private fun modDimension(path: String): ResourceKey<Level> =
        ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(MCTraveler.MOD_ID, path))
}
