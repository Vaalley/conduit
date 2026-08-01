package eu.mctraveler.importer

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import eu.mctraveler.embassy.EmbassyDestination
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionWorlds
import java.util.UUID

/**
 * The Nucleus-era `regions.json` codec (spec User Story 38).
 *
 * Nucleus kept its regions in a *different* file format from the Portal's: a
 * top-level JSON array, `kotlinx.serialization`'s rendering of its own
 * `RegionData`, with corners as nested `start`/`end` points rather than the
 * flat `start-x`/`start-z` keys the live store uses:
 *
 * ```
 * [ { "title": …, "start": { "x": …, "z": …, "y": …? },
 *     "end": { … }, "world": "embassies", "members": [ "<uuid>", … ],
 *     "flags": [ "EMBASSY" ]?, "regions": [ …recursive ],
 *     "metadata": { "embassy-destination": { … } }? } ]
 * ```
 *
 * `flags`, `metadata` and a point's `y` are omitted at their defaults (the
 * encoder's `encodeDefaults = false`), so all three are optional here.
 *
 * Everything else is carried across unchanged: the world string stays the
 * legacy `embassies` the live store also keys by ([RegionWorlds.EMBASSIES]),
 * and `metadata` is carried as the raw JSON it arrived as — Gson keeps a
 * number's original literal, so a destination's `64.0` is still `64.0` after
 * the live store rewrites the file.
 */
object NucleusRegions {

    /**
     * Nucleus's own default bounds for a point that omitted its `y`, from its
     * `RegionData.toRegion`: the world's build height at the top and the
     * literal 15 at the bottom. Not the live store's defaults (320 / −64) —
     * a region that omitted a bound meant what Nucleus read it as, and the
     * conversion is not the place to change a region's height.
     *
     * The twenty real embassy regions all carry explicit 320 / −64 bounds, so
     * neither default is expected to fire; they exist so the conversion is
     * faithful rather than lucky.
     */
    private const val NUCLEUS_DEFAULT_START_Y = 320
    private const val NUCLEUS_DEFAULT_END_Y = 15

    /** The keys an `embassy-destination` must carry, in the order it writes them. */
    private val DESTINATION_NUMBERS = listOf("x", "y", "z", "yaw", "pitch")

    /**
     * Every region of [text] — a Nucleus `regions.json` — that lives in the
     * legacy world [world], as the live store's model. Regions of every other
     * world are ignored: this importer runs *after* the Portal cutover, whose
     * own migration already carried the rest.
     */
    fun regionsIn(text: String, world: String): List<Region> {
        val root = try {
            JsonParser.parseString(text)
        } catch (malformed: JsonSyntaxException) {
            throw IllegalArgumentException("the Nucleus regions.json is not JSON: ${malformed.message}", malformed)
        }
        require(root.isJsonArray) {
            "the Nucleus regions.json is not a JSON array of regions (it starts with \"${text.take(1)}\")"
        }
        return root.asJsonArray
            .map { entry ->
                require(entry.isJsonObject) { "the Nucleus regions.json holds something that is not a region" }
                entry.asJsonObject
            }
            .filter { stringOrNull(it, "world") == world }
            .map { convert(it, parent = null) }
    }

    /**
     * The embassies whose stored destination names a world this server does not
     * have, as the operator reads them. Not a refusal: a destination naming a
     * world nobody kept is simply not somewhere to go
     * ([RegionWorlds.dimensionFor]), and the embassy itself is still worth
     * importing — but it is worth being told about before players find it.
     */
    fun unknownDestinationWorlds(regions: List<Region>): List<String> =
        regions.mapNotNull { region ->
            val world = EmbassyDestination.of(region)?.world ?: return@mapNotNull null
            if (RegionWorlds.dimensionFor(world) != null) null else "${region.title} → \"$world\""
        }

    private fun convert(data: JsonObject, parent: Region?): Region {
        val title = string(data, "title")
        val start = point(data, "start", title)
        val end = point(data, "end", title)
        val region = Region(
            title = title,
            world = string(data, "world"),
            startX = int(start, "x", title),
            startZ = int(start, "z", title),
            endX = int(end, "x", title),
            endZ = int(end, "z", title),
            startY = start.get("y")?.let { int(start, "y", title) } ?: NUCLEUS_DEFAULT_START_Y,
            endY = end.get("y")?.let { int(end, "y", title) } ?: NUCLEUS_DEFAULT_END_Y,
        )
        region.parent = parent
        array(data, "members", title).forEach { member ->
            region.members.add(
                try {
                    UUID.fromString(member.asString)
                } catch (notAUuid: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "region \"$title\" has a member that is not a uuid: $member",
                        notAUuid,
                    )
                },
            )
        }
        array(data, "flags", title).forEach { region.flags.add(it.asString) }
        data.get("metadata")?.let { metadata ->
            require(metadata.isJsonObject) { "region \"$title\" has a \"metadata\" that is not an object" }
            metadata.asJsonObject.entrySet().forEach { (key, value) -> region.metadata[key] = value }
            checkDestination(title, region.metadata[EmbassyDestination.KEY])
        }
        array(data, "regions", title).forEach { sub ->
            require(sub.isJsonObject) { "region \"$title\" has a sub-region that is not an object" }
            region.subRegions.add(convert(sub.asJsonObject, parent = region))
        }
        return region
    }

    /**
     * An `embassy-destination` the anchor could not read is corrupt data, not a
     * shape to guess at: it is what an imported embassy's whole point rests on,
     * and a plot that silently teleports nowhere is worse than a refused import.
     * A region with *no* destination is fine — an owner who never set one.
     */
    private fun checkDestination(title: String, destination: JsonElement?) {
        if (destination == null) return
        require(destination.isJsonObject) { "embassy \"$title\" has an embassy-destination that is not an object" }
        val fields = destination.asJsonObject
        for (key in DESTINATION_NUMBERS) {
            val value = fields.get(key)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                "embassy \"$title\" has an embassy-destination with no numeric \"$key\""
            }
        }
        val world = fields.get("world")
        require(world != null && world.isJsonPrimitive && world.asJsonPrimitive.isString) {
            "embassy \"$title\" has an embassy-destination with no \"world\" string"
        }
    }

    private fun point(data: JsonObject, key: String, title: String): JsonObject {
        val point = data.get(key)
        require(point != null && point.isJsonObject) { "region \"$title\" has no \"$key\" point" }
        return point.asJsonObject
    }

    private fun array(data: JsonObject, key: String, title: String): List<JsonElement> {
        val value = data.get(key) ?: return emptyList()
        require(value.isJsonArray) { "region \"$title\" has a \"$key\" that is not an array" }
        return value.asJsonArray.toList()
    }

    private fun int(data: JsonObject, key: String, title: String): Int {
        val value = data.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "region \"$title\" has no whole-number \"$key\""
        }
        return value.asInt
    }

    private fun stringOrNull(data: JsonObject, key: String): String? =
        data.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun string(data: JsonObject, key: String): String =
        requireNotNull(stringOrNull(data, key)) { "a Nucleus region has no \"$key\" string" }
}
