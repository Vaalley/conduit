package eu.mctraveler.store

import com.google.gson.JsonParser
import eu.mctraveler.economy.Economy
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import net.minecraft.core.BlockPos

class StoreServiceTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `records round trip including item json and can be removed`() {
        val file = dir.resolve("stores.json")
        val frame = UUID.randomUUID()
        val record = StoreRecord(
            frame,
            UUID.randomUUID(),
            "minecraft:overworld",
            BlockPos(1, 2, 3),
            JsonParser.parseString("""{"id":"minecraft:diamond","count":1}"""),
            5,
            20,
            6,
            StoreKind.BUY,
            30,
        )
        StoreService(file).add(record)
        assertEquals(true, file.toFile().readText().contains("\"pricePerItem\": 0.05"))
        val loaded = StoreService(file).byFrame(frame)
        assertNotNull(loaded)
        assertEquals(record, loaded)
        assertNotNull(StoreService(file).remove(frame))
        assertNull(StoreService(file).byFrame(frame))
    }

    @Test
    fun `legacy records use default rows and sell kind`() {
        val frame = UUID.randomUUID()
        val owner = UUID.randomUUID()
        dir.resolve("stores.json").toFile().writeText(
            """
            {"stores":[{
              "frameId":"$frame",
              "owner":"$owner",
              "dimension":"minecraft:overworld",
              "x":1,"y":2,"z":3,
              "item":{"id":"minecraft:diamond","count":1},
              "pricePerItem":5,
              "stock":20
            }]}
            """.trimIndent(),
        )
        val loaded = checkNotNull(StoreService(dir.resolve("stores.json")).byFrame(frame))
        assertEquals(Economy.dollars(5), loaded.pricePerItem)
        assertEquals(3, loaded.rows)
        assertEquals(StoreKind.SELL, loaded.kind)
        assertNull(loaded.wanted)
    }

    @Test
    fun `decimal cents prices round trip`() {
        val file = dir.resolve("stores.json")
        val frame = UUID.randomUUID()
        StoreService(file).add(
            StoreRecord(
                frame,
                UUID.randomUUID(),
                "minecraft:overworld",
                BlockPos(1, 2, 3),
                JsonParser.parseString("""{"id":"minecraft:diamond","count":1}"""),
                5,
                0,
            ),
        )
        assertEquals(5L, checkNotNull(StoreService(file).byFrame(frame)).pricePerItem)
        assertEquals(true, file.toFile().readText().contains("\"pricePerItem\": 0.05"))
    }

    @Test
    fun `missing file starts empty`() {
        assertEquals(emptyList<StoreRecord>(), StoreService(dir.resolve("missing.json")).all().toList())
    }
}
