package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Where a migration parks a save whose owner it could not name, and where a
 * login comes looking for one (spec User Story 43; ticket 20).
 *
 * An offline-mode backend named every save after `md5("OfflinePlayer:" + name)`,
 * which is one-way: at migration time the only name sources are the Portal's
 * `uuid-cache.json` and the backends' `usercache.json`, and on the real
 * deployment those cover a small fraction of the saves. The rest are
 * unidentifiable *at that moment* only — each becomes identifiable the instant
 * its owner joins and hands the server their username. So instead of being left
 * in the retired Portal tree, they are quarantined here:
 *
 * ```
 * <server run dir>/mctraveler/orphaned-saves/
 *   primary/
 *     <offline uuid>.dat
 *     advancements/<offline uuid>.json
 *     stats/<offline uuid>.json
 *   secondary/
 *     …
 * ```
 *
 * Two properties of that layout are load-bearing. The directory sits under the
 * mod's own directory, **outside the level**, so no vanilla file fixer, chunk
 * walker or player-data reader ever sees a save keyed to an identity this server
 * does not use. And the files stay keyed by *offline* uuid, because that hash of
 * a username is the only handle anyone will ever have on them.
 *
 * [OrphanedSaveClaim] is the reader; `PortalImport` is the writer.
 */
class SaveQuarantine(private val root: Path) {

    /**
     * Whether this server has a quarantine at all. False is the normal state —
     * a fresh server, or one whose players have all claimed — and it is the one
     * check every login pays for.
     */
    val isPresent: Boolean get() = Files.isDirectory(root)

    /** One World's save for the player whose username hashes to [offlineUuid]. */
    fun save(world: String, offlineUuid: UUID): Path = worldDir(world).resolve("$offlineUuid.dat")

    fun advancements(world: String, offlineUuid: UUID): Path =
        worldDir(world).resolve("$ADVANCEMENTS/$offlineUuid.json")

    fun stats(world: String, offlineUuid: UUID): Path = worldDir(world).resolve("$STATS/$offlineUuid.json")

    /** Every quarantined file of one player in one World, in no particular order. */
    fun filesOf(world: String, offlineUuid: UUID): List<Path> =
        listOf(save(world, offlineUuid), advancements(world, offlineUuid), stats(world, offlineUuid))

    private fun worldDir(world: String): Path = root.resolve(world)

    companion object {
        /** The quarantine's name inside the mod directory. */
        const val DIRECTORY = "orphaned-saves"

        private const val ADVANCEMENTS = "advancements"
        private const val STATS = "stats"

        /** The quarantine of a server (or a staged migration) whose mod directory is [modDirectory]. */
        fun under(modDirectory: Path): SaveQuarantine = SaveQuarantine(modDirectory.resolve(DIRECTORY))
    }
}
