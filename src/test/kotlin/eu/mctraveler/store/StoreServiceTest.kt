package eu.mctraveler.store

import com.google.gson.JsonParser
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
            10,
            20,
        )
        StoreService(file).add(record)
        val loaded = StoreService(file).byFrame(frame)
        assertNotNull(loaded)
        assertEquals(record, loaded)
        assertNotNull(StoreService(file).remove(frame))
        assertNull(StoreService(file).byFrame(frame))
    }

    @Test
    fun `missing file starts empty`() {
        assertEquals(emptyList<StoreRecord>(), StoreService(dir.resolve("missing.json")).all().toList())
    }
}
