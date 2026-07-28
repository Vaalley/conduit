package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.gametest.framework.GameTestHelper

/**
 * Primary seam: the running server. The scaffold's tracer bullet — a headless
 * in-server test proving the server boots with the mod loaded and initialized.
 */
class ScaffoldGameTest {
    @GameTest
    fun serverBootsWithModInitialized(helper: GameTestHelper) {
        check(FabricLoader.getInstance().isModLoaded(MCTraveler.MOD_ID)) {
            "the ${MCTraveler.MOD_ID} mod is not loaded on the test server"
        }
        check(MCTraveler.initialized) {
            "MCTraveler.onInitialize did not run before the server was ready"
        }
        helper.succeed()
    }
}
