package eu.mctraveler.store

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.mctraveler.economy.Economy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.core.BlockPos

class StoreService(private val file: Path) {
    private val stores = LinkedHashMap<UUID, StoreRecord>()

    init {
        if (Files.exists(file)) load(Files.readString(file))
    }

    fun all(): Collection<StoreRecord> = stores.values

    fun byFrame(frameId: UUID): StoreRecord? = stores[frameId]

    fun add(record: StoreRecord) {
        stores[record.frameId] = record
        save()
    }

    fun remove(frameId: UUID): StoreRecord? {
        val removed = stores.remove(frameId)
        if (removed != null) save()
        return removed
    }

    fun save() {
        file.parent?.let(Files::createDirectories)
        val root = JsonObject()
        val array = JsonArray()
        stores.values.forEach { record ->
            array.add(
                JsonObject().apply {
                    addProperty("frameId", record.frameId.toString())
                    addProperty("owner", record.owner.toString())
                    addProperty("dimension", record.dimension)
                    addProperty("x", record.pos.x)
                    addProperty("y", record.pos.y)
                    addProperty("z", record.pos.z)
                    add("item", record.item.deepCopy())
                    addProperty("pricePerItem", Economy.toDecimal(record.pricePerItem))
                    addProperty("stock", record.stock)
                    addProperty("rows", record.rows)
                    addProperty("kind", record.kind.name)
                    record.wanted?.let { addProperty("wanted", it) }
                },
            )
        }
        root.add("stores", array)
        Files.writeString(file, GSON.toJson(root))
    }

    private fun load(text: String) {
        val stores = JsonParser.parseString(text).asJsonObject.getAsJsonArray("stores") ?: return
        stores.forEach { raw ->
            val json = raw.asJsonObject
            val record = StoreRecord(
                frameId = UUID.fromString(json.get("frameId").asString),
                owner = UUID.fromString(json.get("owner").asString),
                dimension = json.get("dimension").asString,
                pos = BlockPos(json.get("x").asInt, json.get("y").asInt, json.get("z").asInt),
                item = json.get("item").deepCopy(),
                pricePerItem = Economy.fromDecimal(json.get("pricePerItem").asBigDecimal),
                stock = json.get("stock").asInt,
                rows = json.get("rows")?.asInt ?: 3,
                kind = json.get("kind")?.asString?.let {
                    runCatching { StoreKind.valueOf(it) }.getOrDefault(StoreKind.SELL)
                } ?: StoreKind.SELL,
                wanted = json.get("wanted")?.asInt,
            )
            this.stores[record.frameId] = record
        }
    }

    private companion object {
        val GSON = GsonBuilder().setPrettyPrinting().create()
    }
}
