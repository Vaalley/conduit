package eu.mctraveler.persistence

import java.nio.file.Path

/**
 * The Persistence service: the mod's flat-JSON storage, all under one [root]
 * directory (in production `<server run dir>/mctraveler/`):
 *
 * - `players/<uuid>.json` — per-player records ([JsonPlayerStore])
 * - `uuid-cache.json` — the uuid → username cache ([NameCache])
 *
 * Both files keep the Portal's formats, so the importer copies Portal data
 * into this layout as-is. [root] is public because the migration parks data of
 * its own here — the orphaned-save quarantine — and one statement of where the
 * mod's directory is beats two.
 */
class PersistenceService(val root: Path) {
    /**
     * Where the per-player records are, stated once for the same reason [root]
     * is: the merge stamp is a raw field [PlayerStore] does not model, so the
     * migration writes it into these files directly rather than through the
     * store, and two statements of where they live is exactly one too many.
     */
    val playersDir: Path = root.resolve("players")

    val players: PlayerStore = JsonPlayerStore(playersDir)
    val names: NameCache = NameCache(root.resolve("uuid-cache.json"))
}
