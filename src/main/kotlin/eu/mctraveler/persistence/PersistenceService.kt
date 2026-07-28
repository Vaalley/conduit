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
 * into this layout as-is.
 */
class PersistenceService(root: Path) {
    val players: PlayerStore = JsonPlayerStore(root.resolve("players"))
    val names: NameCache = NameCache(root.resolve("uuid-cache.json"))
}
