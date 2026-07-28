package eu.mctraveler.persistence

import java.util.UUID

/**
 * Per-player persistent data: the mod-owned player state that survives restarts
 * (everything vanilla doesn't already persist for us).
 *
 * The typed fields are the mod's live fields — last World and notepad pages.
 * Anything else found in existing player records (legacy fields from the
 * Portal era and before, including the Portal's `isAdmin` — admin status is
 * vanilla operator status now) must pass through every read-modify-write cycle
 * byte-for-byte.
 */
interface PlayerStore {
    /**
     * The World the player was last in — `"primary"` or `"secondary"` — or null
     * for a player with no recorded World. Stored as the Portal's `lastServer`
     * field; mapping World ids to dimension ids is the Worlds service's concern.
     */
    fun lastWorld(uuid: UUID): String?

    fun setLastWorld(uuid: UUID, world: String)

    /**
     * The player's notepad pages, or null if they have never saved a notepad
     * (letting the Notepad feature seed the welcome page for new users).
     */
    fun notepadPages(uuid: UUID): List<String>?

    fun setNotepadPages(uuid: UUID, pages: List<String>)
}
