package eu.mctraveler.importer

import eu.mctraveler.identity.IdentityRemaps
import java.util.UUID

/** One player's three names for themselves across the migration. */
data class PlayerIdentity(
    /** The username the backends knew them by — for the aliased players, the alias. */
    val name: String,
    /** The Mojang UUID the merged server keys everything by. */
    val uuid: UUID,
    /**
     * The UUID the backend's own files are named after: the offline hash of [name]
     * for everything the offline-mode backends wrote, but the Mojang UUID itself for
     * saves already keyed by it (production held both — see `alreadyMojangKeyed`).
     */
    val fileUuid: UUID = OfflineUuid.of(name),
)

/**
 * Who is who, across the Portal's two identity spaces.
 *
 * The Portal's own records (`players/<uuid>.json`, region members) are keyed by
 * Mojang UUID; everything its offline-mode backends wrote is keyed by
 * [OfflineUuid]. Bridging them needs a username for each backend file, and the
 * Portal only ever wrote usernames in one place — `uuid-cache.json`, which in
 * practice it filled from `/op` alone. The importer therefore takes an
 * operator-supplied identity file as well, and the migration refuses to guess:
 * a backend file whose offline UUID resolves to nobody is reported, never
 * silently dropped.
 *
 * Precedence, highest first:
 * 1. [IdentityRemaps] — the two aliased identities. The Portal keyed their data
 *    to the alias (name *and* UUID), and so does the port, so the alias is the
 *    truth however they authenticate; a Mojang lookup of the aliased name would
 *    give a different, wrong UUID.
 * 2. The operator-supplied identities file — the escape hatch for players the
 *    Portal's cache never saw.
 * 3. The Portal's `uuid-cache.json`.
 */
class PlayerIdentities private constructor(private val identities: Map<String, PlayerIdentity>) {

    private val byOfflineUuid: Map<UUID, PlayerIdentity> = identities.values.associateBy { it.fileUuid }

    /** Every identity, in resolution order. */
    val all: Collection<PlayerIdentity> = identities.values

    /** Whose backend file this is, or null — an offline UUID we cannot put a name to. */
    operator fun get(offlineUuid: UUID): PlayerIdentity? = byOfflineUuid[offlineUuid]

    fun byName(name: String): PlayerIdentity? = identities[name]

    companion object {
        /**
         * The index built from [supplied] (username → Mojang UUID, the
         * operator's file) and [uuidCache] (the Portal's Mojang UUID →
         * username cache), with the aliased identities on top.
         *
         * Usernames are matched case-sensitively, exactly as the backends
         * hashed them. Throws if two usernames claim one Mojang UUID — that
         * would merge two players' saves into one.
         */
        fun resolve(supplied: Map<String, UUID>, uuidCache: Map<UUID, String>): PlayerIdentities {
            val identities = LinkedHashMap<String, PlayerIdentity>()
            val claimants = HashMap<UUID, String>()

            fun add(name: String, uuid: UUID) {
                // A name already resolved was resolved by a higher-precedence source.
                if (identities.containsKey(name)) return
                val claimant = claimants.put(uuid, name)
                require(claimant == null) {
                    "\"$claimant\" and \"$name\" both claim uuid $uuid — resolve the conflict in the identities file"
                }
                identities[name] = PlayerIdentity(name, uuid)
            }

            IdentityRemaps.REMAPS.values.forEach { add(it.name(), it.id()) }
            supplied.forEach { (name, uuid) -> add(name, uuid) }
            uuidCache.forEach { (uuid, name) -> add(name, uuid) }
            return PlayerIdentities(identities)
        }
    }
}
