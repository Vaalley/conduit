package eu.mctraveler.region

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.mctraveler.persistence.PortalJson
import java.util.UUID

/**
 * The legacy `regions.json` codec, byte-compatible with the Portal's (and the
 * pre-proxy plugin's) format:
 *
 * ```
 * { "regions": { "<idx>": { title, start-x, start-z, end-x, end-z, world,
 *   members, start-y? (omitted if 320), end-y? (omitted if −64), flags?,
 *   metadata?: { …free-form JSON }, sub-regions?: { …recursive } } } }
 * ```
 *
 * Serialization reproduces the Portal's `JSON.stringify(…, null, 2)` output
 * exactly — key order, 2-space indentation, y defaults omitted, `flags`,
 * `metadata` and `sub-regions` only when non-empty — so a load/save cycle over
 * migrated data is byte-identical. Parsing tolerates omitted y bounds (320/−64
 * defaults) and omitted members/flags/metadata/sub-regions; anything
 * structurally malformed throws, so a file we could not read is never
 * overwritten.
 *
 * `metadata` is the one key the Portal never wrote (deviation 6). It is placed
 * after `flags` and before `sub-regions`; the two never co-occur in practice,
 * since the only regions carrying metadata are embassies and a region cannot
 * be created inside one.
 */
internal object RegionStore {
    fun parse(text: String): MutableList<Region> {
        val root = JsonParser.parseString(text).asJsonObject
        val regions = root.get("regions") ?: return mutableListOf()
        return regions.asJsonObject.entrySet()
            .mapTo(mutableListOf()) { (_, data) -> parseRegion(data.asJsonObject, parent = null) }
    }

    private fun parseRegion(data: JsonObject, parent: Region?): Region {
        val region = Region(
            title = data.get("title").asString,
            world = data.get("world").asString,
            startX = data.get("start-x").asInt,
            startZ = data.get("start-z").asInt,
            endX = data.get("end-x").asInt,
            endZ = data.get("end-z").asInt,
            startY = data.get("start-y")?.asInt ?: Region.DEFAULT_START_Y,
            endY = data.get("end-y")?.asInt ?: Region.DEFAULT_END_Y,
        )
        region.parent = parent
        data.get("members")?.asJsonArray?.forEach { region.members.add(UUID.fromString(it.asString)) }
        data.get("flags")?.asJsonArray?.forEach { region.flags.add(it.asString) }
        data.get("metadata")?.asJsonObject?.entrySet()?.forEach { (key, value) ->
            region.metadata[key] = value
        }
        data.get("sub-regions")?.asJsonObject?.entrySet()?.forEach { (_, sub) ->
            region.subRegions.add(parseRegion(sub.asJsonObject, parent = region))
        }
        return region
    }

    fun serialize(regions: List<Region>): String {
        val out = StringBuilder()
        out.append("{\n  \"regions\": ")
        writeIndexedObject(out, regions, indent = "  ") { region, indent ->
            writeRegion(out, region, indent)
        }
        return out.append("\n}").toString()
    }

    private fun writeRegion(out: StringBuilder, region: Region, indent: String) {
        val inner = "$indent  "
        out.append("{\n")
        out.append(inner).append("\"title\": ").append(PortalJson.encodeString(region.title)).append(",\n")
        out.append(inner).append("\"start-x\": ").append(region.startX).append(",\n")
        out.append(inner).append("\"start-z\": ").append(region.startZ).append(",\n")
        out.append(inner).append("\"end-x\": ").append(region.endX).append(",\n")
        out.append(inner).append("\"end-z\": ").append(region.endZ).append(",\n")
        out.append(inner).append("\"world\": ").append(PortalJson.encodeString(region.world)).append(",\n")
        out.append(inner).append("\"members\": ")
        writeStringArray(out, region.members.map(UUID::toString), inner)
        // The Portal's serializer appended the optional fields after `members`.
        if (region.startY != Region.DEFAULT_START_Y) {
            out.append(",\n").append(inner).append("\"start-y\": ").append(region.startY)
        }
        if (region.endY != Region.DEFAULT_END_Y) {
            out.append(",\n").append(inner).append("\"end-y\": ").append(region.endY)
        }
        if (region.flags.isNotEmpty()) {
            out.append(",\n").append(inner).append("\"flags\": ")
            writeStringArray(out, region.flags.toList(), inner)
        }
        if (region.metadata.isNotEmpty()) {
            out.append(",\n").append(inner).append("\"metadata\": ")
            writeJsonFields(out, region.metadata.entries.map { it.key to it.value }, inner)
        }
        if (region.subRegions.isNotEmpty()) {
            out.append(",\n").append(inner).append("\"sub-regions\": ")
            writeIndexedObject(out, region.subRegions, inner) { sub, subIndent ->
                writeRegion(out, sub, subIndent)
            }
        }
        out.append("\n").append(indent).append("}")
    }

    /** A `{ "0": …, "1": … }` object, as the Portal keyed region lists. */
    private fun writeIndexedObject(
        out: StringBuilder,
        regions: List<Region>,
        indent: String,
        writeValue: (Region, String) -> Unit,
    ) {
        if (regions.isEmpty()) {
            out.append("{}")
            return
        }
        val inner = "$indent  "
        out.append("{\n")
        regions.forEachIndexed { index, region ->
            if (index > 0) out.append(",\n")
            out.append(inner).append("\"").append(index).append("\": ")
            writeValue(region, inner)
        }
        out.append("\n").append(indent).append("}")
    }

    /**
     * A metadata value in the Portal's pretty-printing — the same 2-space
     * shape `JSON.stringify(…, null, 2)` gives any nested object.
     *
     * Numbers are re-emitted from [Number.toString], which for a value Gson
     * parsed out of the file is the original literal (its `LazilyParsedNumber`
     * keeps the raw text): `64.0` does not come back as `64`, so a load/save
     * cycle over a file we did not write is still byte-identical.
     */
    private fun writeJsonValue(out: StringBuilder, value: JsonElement, indent: String) {
        when {
            value.isJsonObject ->
                writeJsonFields(out, value.asJsonObject.entrySet().map { it.key to it.value }, indent)
            value.isJsonArray -> {
                val items = value.asJsonArray.toList()
                if (items.isEmpty()) {
                    out.append("[]")
                    return
                }
                val inner = "$indent  "
                out.append("[\n")
                items.forEachIndexed { index, item ->
                    if (index > 0) out.append(",\n")
                    out.append(inner)
                    writeJsonValue(out, item, inner)
                }
                out.append("\n").append(indent).append("]")
            }
            value.isJsonNull -> out.append("null")
            else -> {
                val primitive = value.asJsonPrimitive
                when {
                    primitive.isString -> out.append(PortalJson.encodeString(primitive.asString))
                    primitive.isBoolean -> out.append(primitive.asBoolean)
                    else -> out.append(primitive.asNumber.toString())
                }
            }
        }
    }

    private fun writeJsonFields(out: StringBuilder, fields: List<Pair<String, JsonElement>>, indent: String) {
        if (fields.isEmpty()) {
            out.append("{}")
            return
        }
        val inner = "$indent  "
        out.append("{\n")
        fields.forEachIndexed { index, (key, value) ->
            if (index > 0) out.append(",\n")
            out.append(inner).append(PortalJson.encodeString(key)).append(": ")
            writeJsonValue(out, value, inner)
        }
        out.append("\n").append(indent).append("}")
    }

    private fun writeStringArray(out: StringBuilder, values: List<String>, indent: String) {
        if (values.isEmpty()) {
            out.append("[]")
            return
        }
        val inner = "$indent  "
        out.append("[\n")
        values.forEachIndexed { index, value ->
            if (index > 0) out.append(",\n")
            out.append(inner).append(PortalJson.encodeString(value))
        }
        out.append("\n").append(indent).append("]")
    }
}
