package eu.mctraveler.importer

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/** One entry of a vanilla `ops.json`, in either identity space. */
data class OpEntry(
    val uuid: UUID,
    val name: String,
    val level: Int,
    val bypassesPlayerLimit: Boolean,
)

/** Re-keyed operators, and the names no identity could be found for. */
data class RekeyedOps(val entries: List<OpEntry>, val unresolved: List<String>)

/**
 * Operator migration (spec User Story 41, deviation register 8).
 *
 * The Portal kept its own admin flag and mirrored it into both backends'
 * `ops.json` under offline UUIDs; the port has no mod-side flag at all, so the
 * backends' op lists are the whole of what carries over, and they land in the
 * one real ops list the single online-mode server reads. Entries are matched
 * by the username they carry — the same username the backend hashed into the
 * UUID beside it — so the aliased players' entries follow their alias.
 *
 * The written format is `ServerOpListEntry`'s (uuid, name, level,
 * bypassesPlayerLimit), pretty-printed as vanilla's own stored-user lists are.
 */
object OpsImport {

    /** The entries of one backend's `ops.json`. */
    fun parse(text: String): List<OpEntry> =
        JsonParser.parseString(text).asJsonArray.map { element ->
            val entry = element.asJsonObject
            OpEntry(
                uuid = UUID.fromString(entry.get("uuid").asString),
                name = entry.get("name").asString,
                level = entry.get("level")?.asInt ?: DEFAULT_LEVEL,
                bypassesPlayerLimit = entry.get("bypassesPlayerLimit")?.asBoolean ?: false,
            )
        }

    /**
     * [entries] — both backends' op lists — as the merged server's single ops
     * list: keyed by Mojang UUID, one entry per operator (the backends were
     * kept in step, so the first entry for a player wins), and every username
     * no [identities] entry answers to reported instead of silently dropped.
     */
    fun rekey(entries: List<OpEntry>, identities: PlayerIdentities): RekeyedOps {
        val operators = LinkedHashMap<UUID, OpEntry>()
        val unresolved = LinkedHashSet<String>()
        for (entry in entries) {
            val identity = identities.byName(entry.name)
            if (identity == null) {
                unresolved.add(entry.name)
                continue
            }
            operators.putIfAbsent(identity.uuid, entry.copy(uuid = identity.uuid, name = identity.name))
        }
        return RekeyedOps(operators.values.toList(), unresolved.toList())
    }

    /** [entries] as an `ops.json` the server reads back. */
    fun serialize(entries: List<OpEntry>): String {
        val array = JsonArray()
        for (entry in entries) {
            array.add(
                JsonObject().apply {
                    addProperty("uuid", entry.uuid.toString())
                    addProperty("name", entry.name)
                    addProperty("level", entry.level)
                    addProperty("bypassesPlayerLimit", entry.bypassesPlayerLimit)
                },
            )
        }
        return GSON.toJson(array)
    }

    private const val DEFAULT_LEVEL = 4
    private val GSON = GsonBuilder().setPrettyPrinting().create()
}
