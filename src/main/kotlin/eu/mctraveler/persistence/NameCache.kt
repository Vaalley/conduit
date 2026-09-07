package eu.mctraveler.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

/**
 * The uuid → username cache: recorded at every player login, answering name
 * lookups for players who are offline (region member lists, `/rg locate`).
 * This is the real cache that replaces the Portal's op-only uuid cache; the
 * file keeps the Portal's `uuid-cache.json` format (one JSON object, dashed
 * lowercase uuid keys, username values) so migrated data slots straight in —
 * the importer seeds it via [record].
 *
 * Loaded once at construction (server start); [record] writes through to disk.
 * All access is expected from the server thread.
 */
class NameCache(private val file: Path) {
    /** Lowercase username -> the uuid key that most recently recorded it. */
    private val latestOwner = HashMap<String, String>()

    private val names: LinkedHashMap<String, String> = load()

    /** The last username seen for [uuid], or null if never seen. */
    fun usernameFor(uuid: UUID): String? = names[uuid.toString()]

    /** The most recently recorded UUID for [username], or null if never seen. */
    fun uuidFor(username: String): UUID? =
        latestOwner[username.lowercase(Locale.ROOT)]?.let(UUID::fromString)

    /** Remember [username] as [uuid]'s name, replacing any previous name. */
    fun record(uuid: UUID, username: String) {
        val key = uuid.toString()
        val lower = username.lowercase(Locale.ROOT)
        if (names[key] == username && latestOwner[lower] == key) return

        // LinkedHashMap iteration order records the latest observed owner of a
        // name, while preserving historical UUID -> name display data.
        val old = names[key]
        names.remove(key)
        names[key] = username
        latestOwner[lower] = key
        // A rename leaves the old name's owner pointing at this uuid; re-seat
        // it on whoever recorded that name most recently (renames are rare,
        // so the one scan there is fine).
        if (old != null && !old.equals(username, ignoreCase = true)) {
            val oldLower = old.lowercase(Locale.ROOT)
            val owner = names.entries.lastOrNull { it.value.equals(old, ignoreCase = true) }?.key
            if (owner == null) latestOwner.remove(oldLower) else latestOwner[oldLower] = owner
        }
        save()
    }

    private fun load(): LinkedHashMap<String, String> {
        if (Files.notExists(file)) return LinkedHashMap()
        val entries = PortalJson.parse(Files.readString(file))
        return entries.entries.associateTo(LinkedHashMap()) { (uuid, field) ->
            val name = PortalJson.decodeString(field.rawValue)
            // In file order, so the last writer wins each name.
            latestOwner[name.lowercase(Locale.ROOT)] = uuid
            uuid to name
        }
    }

    private fun save() {
        file.parent?.let(Files::createDirectories)
        val fields = names.map { (uuid, name) ->
            PortalJson.Field(PortalJson.encodeString(uuid), PortalJson.encodeString(name))
        }
        Files.writeString(file, PortalJson.emit(fields))
    }
}
