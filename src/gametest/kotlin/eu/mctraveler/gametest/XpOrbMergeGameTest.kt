package eu.mctraveler.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory

/**
 * The Portal's orb-merge effect, server-side (spec User Story 45; deviation register 12):
 * a grinder-style burst of experience-orb spawns must collapse to a handful of orb
 * entities whose values sum to the full amount — no XP lost.
 *
 * Written first as a probe of vanilla 26.2's own orb clumping, which turned out to
 * deliver only part of the effect: 100 same-tick awards of a single orb size still left
 * 38 orb entities, and a follow-up burst grew the pile to 68 (vanilla gates merging
 * behind a random id-mod-40 bucket). `ExperienceOrbMixin` drops that gate, making the
 * merge deterministic; this test now guards both the collapse and the exact XP total.
 */
class XpOrbMergeGameTest {
    private val logger = LoggerFactory.getLogger("mctraveler-test")

    @GameTest(maxTicks = 130)
    fun burstOfOrbSpawnsCollapsesWithTotalXpPreserved(helper: GameTestHelper) {
        // Deterministic floor so the orbs settle inside the structure bounds.
        for (x in 0..7) {
            for (z in 0..7) {
                helper.setBlock(x, 0, z, Blocks.STONE)
            }
        }

        // One grinder-burst: many kills' worth of XP awarded at one spot in one tick.
        val spawn = helper.absoluteVec(Vec3(4.0, 1.5, 4.0))
        repeat(BURST_KILLS) {
            ExperienceOrb.award(helper.level, spawn, XP_PER_KILL)
        }

        // Give the periodic orb merge scan (every 20 ticks) time to run a couple of times.
        helper.runAfterDelay(SETTLE_TICKS) {
            val census = measureOrbs(helper, "first burst")
            helper.assertValueEqual(
                census.totalXp,
                BURST_KILLS * XP_PER_KILL,
                "total XP held by orbs after the first burst",
            )
            assertVisiblyFewer(helper, census)

            // A grinder keeps going: a second burst at the same spot must fold into
            // the orbs already lying there, not double the entity count.
            repeat(BURST_KILLS) {
                ExperienceOrb.award(helper.level, spawn, XP_PER_KILL)
            }
        }

        helper.runAfterDelay(2 * SETTLE_TICKS) {
            val census = measureOrbs(helper, "second burst")
            helper.assertValueEqual(
                census.totalXp,
                2 * BURST_KILLS * XP_PER_KILL,
                "total XP held by orbs after the second burst",
            )
            assertVisiblyFewer(helper, census)
            helper.succeed()
        }
    }

    /** What the burst left behind: how many orb entities, holding how much XP in total. */
    private data class OrbCensus(val entityCount: Int, val totalXp: Int)

    private fun measureOrbs(helper: GameTestHelper, phase: String): OrbCensus {
        val orbs = helper.getEntities(EntityTypes.EXPERIENCE_ORB)
        val census = OrbCensus(orbs.size, orbs.sumOf { totalXpOf(it, helper) })
        logger.info(
            "XP orb merge probe ({}): {} orb entities holding {} XP total",
            phase, census.entityCount, census.totalXp,
        )
        return census
    }

    private fun assertVisiblyFewer(helper: GameTestHelper, census: OrbCensus) {
        helper.assertTrue(
            census.entityCount <= MAX_MERGED_ORBS,
            "expected the $BURST_KILLS-kill bursts to collapse to at most " +
                "$MAX_MERGED_ORBS orb entities, but found ${census.entityCount}",
        )
    }

    /**
     * Total XP a player would collect from this orb: its per-pickup value times its
     * stacked count. The entity count above is the player-visible half of the claim;
     * the total deliberately comes from the orb's own save data (`Value`/`Count`, the
     * format the game persists) rather than a player picking everything up — the count
     * has no accessor, and a headless mock player cannot tick through hundreds of
     * pickup-delay cycles inside one gametest.
     */
    private fun totalXpOf(orb: ExperienceOrb, helper: GameTestHelper): Int {
        val output = TagValueOutput.createWithContext(
            ProblemReporter.DISCARDING,
            helper.level.registryAccess(),
        )
        orb.saveWithoutId(output)
        val tag = output.buildResult()
        return tag.getShortOr("Value", 0).toInt() * tag.getIntOr("Count", 1)
    }

    private companion object {
        /** Kills' worth of XP in one burst — a busy grinder moment. */
        const val BURST_KILLS = 100

        /**
         * XP per kill: a zombie's 5, which vanilla splits into orbs of 3 + 1 + 1 — so
         * every burst exercises merging across mixed orb value classes, not just one.
         */
        const val XP_PER_KILL = 5

        /**
         * With deterministic merging a burst leaves one orb per value class per spot
         * (values 3 and 1 here); a later burst can add at most one more per class
         * before the periodic scan re-merges them. The margin covers orbs drifting
         * apart faster than the scan catches them. Vanilla's own clumping (38 orbs
         * after one single-class burst, 68 after two) fails this bound — the guard is
         * on our merging, not vanilla's.
         */
        const val MAX_MERGED_ORBS = 8

        /** Two full merge-scan periods (20 ticks each) plus settling margin. */
        const val SETTLE_TICKS = 50L
    }
}
