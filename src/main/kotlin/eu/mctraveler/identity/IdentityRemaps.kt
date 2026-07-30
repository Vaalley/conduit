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

    /** The uuids the server sees an aliased player under — [REMAPS]'s targets. */
    private val ALIAS_IDS: Set<UUID> = REMAPS.values.mapTo(HashSet(), GameProfile::id)

    /**
     * Returns the profile the server should see for [profile]: the aliased name
     * and uuid (logged with the Portal's remap line) carrying the authenticated
     * profile's own property map, the profile itself untouched for everyone else.
     *
     * The properties are what the Portal's `SetProfileProperties` hook captured
     * and injected into its player-info packets (inventory §1, §2.18): the
     * Mojang-signed `textures` blob is signed over its own payload, not over the
     * profile it hangs on, so it still validates once it is hanging on the alias
     * and the aliased player renders with their real skin.
     */
    @JvmStatic
    fun remap(profile: GameProfile): GameProfile {
        val target = REMAPS[profile.name()] ?: return profile
        MCTraveler.LOGGER.info("Remapping {} -> {}", profile.name(), target.name())
        return GameProfile(target.id(), target.name(), profile.properties())
    }

    /**
     * Whether [id] is an alias this table hands out — i.e. whether the player
     * holding it may be one of the two aliased identities.
     *
     * Such a player's client is told a uuid that is not the one it authenticated
     * with, so vanilla's client never builds a chat session for it and their chat
     * can never be signed; this is the predicate that exempts exactly them from
     * secure-chat enforcement. It cannot tell an aliased player apart from the
     * real account that owns the alias — by construction, that is what a remap is.
     */
    @JvmStatic
    fun isAliased(id: UUID): Boolean = id in ALIAS_IDS
}
