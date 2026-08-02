package eu.mctraveler.persistence

import java.util.UUID

/**
 * Per-player persistent data: the mod-owned player state that survives restarts
 * (everything vanilla doesn't already persist for us).
 *
 * The typed fields are the mod's live fields — last World and notepad pages.
 * Anything else found in existing player records (legacy fields from the
 * Portal era and before, including the Portal's `isAdmin` — admin status is
 * vanilla operator status now, and the `worlds` object that held the Per-World
 * Buckets, which the merge retired) must pass through every read-modify-write
 * cycle byte-for-byte.
 */
interface PlayerStore {
    /**
     * The World the player was last in — `"primary"` or `"secondary"` — or null
     * for a player with no recorded World. Stored as the Portal's `lastServer`
     * field.
     *
     * The merge left one World, and the sweep rewrote every record it saw to
     * name it, so on a merged server this answers `"primary"` for everyone who
     * was here on the night. It is kept because the field is still the one thing
     * that can tell an *unswept* record apart — the quarantined saves the claim
     * path picks up years later are exactly that case (see
     * [eu.mctraveler.importer.OrphanedSaveClaim]).
     */
    fun lastWorld(uuid: UUID): String?

    fun setLastWorld(uuid: UUID, world: String)

    /**
     * The player's notepad pages, or null if they have never saved a notepad
     * (letting the Notepad feature seed the welcome page for new users).
     */
    fun notepadPages(uuid: UUID): List<String>?

    fun setNotepadPages(uuid: UUID, pages: List<String>)

    /**
     * The player's Teleportation Crystal energy (0-3), or null if they have
     * never spent any — read as full energy by
     * [eu.mctraveler.crystal.CrystalEnergy], which owns the range.
     */
    fun crystalEnergy(uuid: UUID): Int?

    fun setCrystalEnergy(uuid: UUID, energy: Int)

    /**
     * The play-time tick count at which the player's next energy point is due,
     * or null when no recharge is pending (they are at full energy). Play time,
     * not wall clock: an offline player's recharge does not advance.
     */
    fun crystalNextRegenAt(uuid: UUID): Int?

    /** Sets, or with a null [playTimeTicks] clears, the pending recharge. */
    fun setCrystalNextRegenAt(uuid: UUID, playTimeTicks: Int?)
}
