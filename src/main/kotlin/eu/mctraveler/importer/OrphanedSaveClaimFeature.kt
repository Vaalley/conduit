package eu.mctraveler.importer

import eu.mctraveler.MCTraveler
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.players.NameAndId
import net.minecraft.world.level.storage.LevelResource

/**
 * Wiring for the orphaned-save claim (ticket 20): builds the claim at server
 * start against the live server's own directories, and is the one entry point
 * `PlayerDataStorageMixin` calls.
 *
 * The hook has to be a mixin rather than a Fabric login event because timing is
 * the whole point: the claim must put the save in place *before*
 * `PlayerDataStorage.load` reads it, so that the login path stays completely
 * ordinary and the player arrives where the Portal left them instead of at world
 * spawn. `PlayerDataStorage.load(NameAndId)` is the deepest common point — both
 * reads of a login (the configuration phase's `PrepareSpawnTask`, which picks the
 * dimension and position, and the spawn that follows) go through it, and the
 * second finds the claim already done.
 *
 * Everything else about the claim — including the guard that protects a live
 * player — is [OrphanedSaveClaim]'s; this file only supplies paths and turns an
 * outcome into a log line an operator can audit a cutover with.
 */
object OrphanedSaveClaimFeature {

    /**
     * The live claim, or null before the first server start (and in the importer
     * tool, which registers nothing) — the mixin is then inert.
     */
    private var claim: OrphanedSaveClaim? = null

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            val persistence = checkNotNull(MCTraveler.persistence)
            claim = OrphanedSaveClaim(
                quarantine = SaveQuarantine.under(persistence.root),
                // Vanilla's own directories for the three per-player files, so a
                // claim writes exactly where the login path reads.
                playerdata = server.getWorldPath(LevelResource.PLAYER_DATA_DIR),
                advancements = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR),
                stats = server.getWorldPath(LevelResource.PLAYER_STATS_DIR),
                players = persistence.players,
            )
        }
    }

    /** Called from `PlayerDataStorageMixin`, before vanilla reads [who]'s save. */
    @JvmStatic
    fun claimBefore(who: NameAndId) {
        val claim = this.claim ?: return
        report(claim.claim(who.id(), who.name()))
    }

    /**
     * The audit trail a cutover is checked against. A claim is invisible to the
     * player by design, so the log is the only place it happens.
     */
    private fun report(outcome: ClaimOutcome) {
        when (outcome) {
            ClaimOutcome.NoOrphan -> Unit
            is ClaimOutcome.Claimed -> MCTraveler.LOGGER.info(
                "orphaned-save claim: {} ({}) claimed their Portal save — live World {}, {}, DataVersion {}",
                outcome.username,
                outcome.uuid,
                outcome.liveWorld,
                outcome.bucketWorld?.let { "Per-World Bucket seeded for $it" } ?: "no save in the other World",
                if (outcome.dataVersion == OrphanedSaveClaim.UNKNOWN_DATA_VERSION) {
                    "absent"
                } else {
                    outcome.dataVersion
                },
            )
            is ClaimOutcome.AlreadyLive -> MCTraveler.LOGGER.warn(
                "orphaned-save claim: skipped for {} ({}) — they already have a save on this server, so the " +
                    "quarantined save under their name was left untouched. Check whether that username " +
                    "changed hands before assuming the save is theirs.",
                outcome.username,
                outcome.uuid,
            )
            is ClaimOutcome.Failed -> MCTraveler.LOGGER.error(
                "orphaned-save claim: FAILED for {} ({}): {}. {} Once they play and get a save of their " +
                    "own the claim will be refused for good, so resolve this first.",
                outcome.username,
                outcome.uuid,
                outcome.reason,
                if (outcome.anythingWritten) {
                    "It failed part-way through writing, so check what landed before retrying."
                } else {
                    "Nothing was written and the quarantine is intact."
                },
            )
        }
    }
}
