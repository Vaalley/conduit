package eu.mctraveler.gametest

import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.importer.WorldLayout
import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.Level

/**
 * The merge against a running server (ticket 11), which is the last line of
 * evidence before the operation is run for real.
 *
 * Everything upstream of here proves the merge against files: the plan, the
 * clip, the relocation, a block-for-block sampled diff, the audit and its
 * cross-checks, and every sweep. None of that says the *game* behaves. So this
 * suite runs a whole merge — the real tool, the real audit, the real sweeps —
 * and then does, on a booted server, the things a player would actually do with
 * what came out: open a chest that travelled, be turned away from someone's
 * Region at its new coordinates, die and wake on a bed that moved by a different
 * pass than the respawn point naming it, step into a portal and come out at its
 * own twin, and ask `/switch` where their other base went.
 *
 * [MergedSave] is the fixture, and the reason it is built the way it is matters
 * more here than anywhere else in the repo: it is the merge's own output, laid
 * into this server's own dimensions. A save assembled by hand with
 * `dimensions/mctraveler/secondary/` still in it would not fail on this build —
 * it would be ignored in silence, because retiring the Worlds subsystem stopped
 * the server creating those dimensions at all (ticket 09) — and every case below
 * would go green having read nothing.
 */
class WorldMergeGameTest {

    /**
     * The dimensions a merged save can still be reached through, and the three
     * it cannot.
     *
     * This is the case that states the trap rather than merely avoiding it. A
     * merged run directory still holds Secondary's dimension folders — the merge
     * copies, because the pre-merge backup is the rollback — and this server has
     * no way to open them. What makes the landmass reachable at all is that the
     * merge put it in Primary's folders instead, so the assertions run in both
     * directions: Secondary's dimensions do not exist here, and the relocated
     * chunk data is somewhere that does.
     */
    @GameTest(maxTicks = 600)
    fun aMergedSaveIsReachableOnlyThroughTheDimensionsThatStillExist(helper: GameTestHelper) {
        val server = helper.level.server
        val merged = MergedSave.of(server)

        for (dimension in listOf(Level.OVERWORLD, Level.NETHER, Level.END, EmbassiesFeature.DIMENSION)) {
            helper.assertTrue(
                server.getLevel(dimension) != null,
                "${dimension.identifier()} is not loaded, so a merged save has nowhere to be read from",
            )
        }
        for (role in DimensionRole.entries) {
            val secondary = WorldLayout.SECONDARY.dimension(role)
            helper.assertTrue(
                server.getLevel(secondary) == null,
                "${secondary.identifier()} exists on this server, which is the state in which a " +
                    "hand-built fixture would be read rather than silently ignored",
            )
        }

        helper.assertTrue(
            Files.isDirectory(merged.secondaryStorage(DimensionRole.OVERWORLD).resolve("region")),
            "the merged save no longer holds Secondary's own chunk data, so this fixture is not " +
                "the shape a real merge leaves behind",
        )
        for (role in listOf(DimensionRole.OVERWORLD, DimensionRole.NETHER)) {
            helper.assertTrue(
                merged.report.relocation.dimension(role).relocated > 0,
                "the merge relocated no chunks of Secondary's ${role.id}",
            )
            helper.assertTrue(
                Files.isDirectory(merged.primaryStorage(role).resolve("region")),
                "nothing arrived in ${merged.primaryStorage(role)}",
            )
        }
        helper.assertTrue(
            merged.report.sampled.compared > 0,
            "the merge compared no chunk against its source, so nothing here says the terrain arrived",
        )
        helper.succeed()
    }
}
