package eu.mctraveler.persistence

import java.nio.file.Files
import java.nio.file.Path
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
    private val names: LinkedHashMap<String, String> = load()

    /** The last username seen for [uuid], or null if never seen. */
    fun usernameFor(uuid: UUID): String? = names[uuid.toString()]

    /** Remember [username] as [uuid]'s name, replacing any previous name. */
    fun record(uuid: UUID, username: String) {
        val replaced = names.put(uuid.toString(), username)
        if (replaced != username) save()
    }

    private fun load(): LinkedHashMap<String, String> {
        if (Files.notExists(file)) return LinkedHashMap()
        val entries = PortalJson.parse(Files.readString(file))
        return entries.entries.associateTo(LinkedHashMap()) { (uuid, field) ->
            uuid to PortalJson.decodeString(field.rawValue)
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
