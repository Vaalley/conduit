package eu.mctraveler.identity

import com.mojang.authlib.GameProfile
import eu.mctraveler.MCTraveler
import java.util.UUID

/**
 * The two aliased identities (spec User Story 42), ported from the Portal's
 * TravelPatchFeature: DemonicNoodle and AlsoJames authenticate with Mojang
 * under their real accounts but play as travelcraft2012 and iElmo — name and
 * UUID both — so everything keyed to a profile (playerdata, regions, tab
 * list, name cache) sees only the remapped identity.
 */
object IdentityRemaps {

    /**
     * The remap table, exactly the Portal's TravelPatchFeature constants
     * (inventory §2.9). Keyed by the authenticated username, case-sensitive.
     * The importer (ticket 18) mirrors this table when re-keying legacy data.
     */
    val REMAPS: Map<String, GameProfile> = mapOf(
        "DemonicNoodle" to
            GameProfile(UUID.fromString("461789c5-4501-48a0-b47d-7574c9a7b9ec"), "travelcraft2012"),
        "AlsoJames" to
            GameProfile(UUID.fromString("be9482bb-6bcd-4df3-9cf4-9f1fb61c5e93"), "iElmo"),
    )

    /**
     * Returns the profile the server should see for [profile]: a fresh copy of
     * the aliased identity when the username is in [REMAPS] (logged with the
     * Portal's remap line), the profile itself untouched for everyone else.
     */
    @JvmStatic
    fun remap(profile: GameProfile): GameProfile {
        val target = REMAPS[profile.name()] ?: return profile
        MCTraveler.LOGGER.info("Remapping {} -> {}", profile.name(), target.name())
        return GameProfile(target.id(), target.name())
    }
}
