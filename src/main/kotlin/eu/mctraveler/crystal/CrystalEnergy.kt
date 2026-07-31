package eu.mctraveler.crystal

import eu.mctraveler.MCTraveler
import eu.mctraveler.persistence.PlayerStore
import java.util.UUID
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats

/**
 * Teleportation Crystal energy (spec User Stories 24-25; Nucleus
 * `modifyEnergy`/`getEnergy` and its regen task).
 *
 * Energy is a per-*player* pool of 0-3 points shared by every crystal they
 * carry, not a per-item charge — which is why the damage bar is painted per
 * viewer ([CrystalDamageDisplay]) rather than stored on the stack.
 *
 * Recharging is measured in **play time**, not wall clock (the house rule, and
 * Nucleus's own choice): the clock starts the moment a full player first spends
 * a point and is re-armed 15 play-time minutes ahead for each point after,
 * until the player is full again — at which point it stops entirely. A player
 * who logs off half-recharged resumes exactly where they left off, because the
 * threshold is a `Stats.PLAY_TIME` value and that stat only advances in-game.
 *
 * The store-shaped functions are the whole behaviour; the [ServerPlayer] ones
 * are the thin façade the rest of the mod (and ticket 04's menu) uses.
 */
object CrystalEnergy {

    /** A full pool, and the value a player with no stored energy reads as. */
    const val MAX_ENERGY = 3

    /** Nucleus's `kRechargeMinutes`, quoted in the crystal's own lore. */
    const val RECHARGE_MINUTES = 15

    /** [RECHARGE_MINUTES] as the play-time ticks one energy point costs. */
    const val RECHARGE_TICKS = RECHARGE_MINUTES * 60 * 20

    // -- store-shaped: the behaviour, and what the importer (ticket 05) writes --

    /** [uuid]'s energy; a player who has never spent any is full. */
    fun energyOf(store: PlayerStore, uuid: UUID): Int =
        (store.crystalEnergy(uuid) ?: MAX_ENERGY).coerceIn(0, MAX_ENERGY)

    /** The play-time tick [uuid]'s next point is due at, or null when none is pending. */
    fun nextRegenAt(store: PlayerStore, uuid: UUID): Int? = store.crystalNextRegenAt(uuid)

    /**
     * Moves [uuid]'s energy by [delta], clamped to 0..[MAX_ENERGY], and returns
     * the new value. Leaving a full pool with no recharge pending starts the
     * clock at [playTimeTicks] + [RECHARGE_TICKS]; spending further points
     * never pushes an already-running clock back.
     */
    fun modify(store: PlayerStore, uuid: UUID, delta: Int, playTimeTicks: Int): Int {
        val current = energyOf(store, uuid)
        val proposed = (current + delta).coerceIn(0, MAX_ENERGY)
        store.setCrystalEnergy(uuid, proposed)
        if (current == MAX_ENERGY && proposed < MAX_ENERGY && nextRegenAt(store, uuid) == null) {
            store.setCrystalNextRegenAt(uuid, playTimeTicks + RECHARGE_TICKS)
        }
        return proposed
    }

    /**
     * Grants [uuid] one energy point if one is due at [playTimeTicks], and
     * returns whether it did. A missing threshold reads as "due now" — Nucleus's
     * own reading, and the state an imported player arrives in with energy but
     * no recorded threshold. Reaching [MAX_ENERGY] stops the clock; below it,
     * the clock is re-armed a further [RECHARGE_TICKS] out.
     */
    fun regen(store: PlayerStore, uuid: UUID, playTimeTicks: Int): Boolean {
        val current = energyOf(store, uuid)
        if (current >= MAX_ENERGY) return false
        val threshold = nextRegenAt(store, uuid)
        if (threshold != null && playTimeTicks < threshold) return false
        modify(store, uuid, 1, playTimeTicks)
        if (current >= MAX_ENERGY - 1) {
            store.setCrystalNextRegenAt(uuid, null)
        } else {
            store.setCrystalNextRegenAt(uuid, playTimeTicks + RECHARGE_TICKS)
        }
        return true
    }

    // -- player-shaped façade --

    /** [player]'s energy. */
    fun energyOf(player: ServerPlayer): Int = energyOf(store(), player.uuid)

    /**
     * Moves [player]'s energy by [delta] (see the store-shaped [modify]) and
     * resyncs their open container, so every crystal they can see immediately
     * wears the new reading — Nucleus's `player.updateInventory()`.
     */
    fun modify(player: ServerPlayer, delta: Int): Int {
        val energy = modify(store(), player.uuid, delta, playTimeTicks(player))
        resync(player)
        return energy
    }

    /** Sets [player]'s energy outright (the admin command's one job). */
    fun setEnergy(player: ServerPlayer, energy: Int): Int =
        modify(player, energy - energyOf(player))

    /** Ticks played, the clock every crystal threshold is measured against. */
    fun playTimeTicks(player: ServerPlayer): Int =
        player.stats.getValue(Stats.CUSTOM, Stats.PLAY_TIME)

    /**
     * Resends the player's open container, so the crystals in it are repainted
     * with their current energy on the way out.
     */
    fun resync(player: ServerPlayer) {
        player.containerMenu.sendAllDataToRemote()
    }

    private fun store(): PlayerStore =
        checkNotNull(MCTraveler.persistence) { "the Teleportation Crystal needs the Persistence service" }.players
}
