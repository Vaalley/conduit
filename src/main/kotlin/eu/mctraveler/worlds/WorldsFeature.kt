package eu.mctraveler.worlds

import eu.mctraveler.MCTraveler
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

/**
 * Wiring for what is left of the Worlds subsystem: `/switch`, and the merge
 * artifact it reads.
 *
 * There used to be a Worlds service here — the two-World topology, Travel, the
 * Per-World Bucket, and a login hook that routed every arriving player into the
 * World their record named. The merge retired all of it, because there is one
 * map now and nothing left to route between. What survives is the one thing the
 * merge made *more* necessary: the command players will type first, and the file
 * that lets it answer them.
 *
 * The name is kept deliberately. This still wires the `worlds` package, which is
 * still where `/switch` lives, and renaming it without renaming the package
 * would be half a rename that tells a future reader less than the KDoc does.
 */
object WorldsFeature {

    /**
     * The merge's banked positions, which `/switch` reads back to tell a player
     * where their other base went. Bound to the file at server start rather than
     * to its contents — nothing is read until the first player asks, and an
     * unmerged server has no file to read at all.
     */
    var bankedPositions: BankedPositions? = null
        private set

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { _ ->
            val persistence = checkNotNull(MCTraveler.persistence)
            bankedPositions = BankedPositions(persistence.root.resolve(BankedPositions.FILE_NAME))
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            SwitchCommand.register(dispatcher) { checkNotNull(bankedPositions) }
        }
    }
}
