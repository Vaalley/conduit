package eu.mctraveler.importer

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The offline-UUID scheme, ported solely for migration (spec Implementation
 * Decisions: Importer).
 *
 * The Portal authenticated players against Mojang itself and ran its two
 * backends in offline mode, so everything the backends wrote — playerdata,
 * advancements, statistics, `ops.json` — is keyed by the UUID an offline-mode
 * server derives from the username: `md5("OfflinePlayer:" + name)` stamped as
 * a version-3 UUID. The single online-mode Fabric server keys everything by
 * the player's real Mojang UUID instead, so this computation exists only to
 * find which legacy file belongs to whom, and has no place in live gameplay.
 */
object OfflineUuid {
    /**
     * The UUID an offline-mode server gives [username]. Case-sensitive: the
     * backends hashed the name exactly as the player logged in with it.
     */
    fun of(username: String): UUID =
        // Java's name-based UUID is md5 + the version-3 bit stamping, i.e.
        // precisely what the backends (and the Portal's own copies of this
        // function) computed.
        UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(StandardCharsets.UTF_8))
}
