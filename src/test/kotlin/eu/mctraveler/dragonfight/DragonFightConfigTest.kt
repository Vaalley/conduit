package eu.mctraveler.dragonfight

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DragonFightConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `missing config gets the agreed defaults`() {
        val file = tempDir.resolve(DragonFightConfig.CONFIG_FILE)
        assertEquals(DragonFightConfig.DEFAULTS, DragonFightConfig.load(file))
        assertTrue(Files.exists(file))
        assertTrue(Files.readString(file).contains("\"borderRadius\": 300"))
    }

    @Test
    fun `partial config keeps omitted defaults`() {
        val file = tempDir.resolve(DragonFightConfig.CONFIG_FILE)
        Files.writeString(file, """{ "borderRadius": 500 }""")
        assertEquals(
            DragonFightConfig.Settings(10, 10, 500, 168),
            DragonFightConfig.load(file),
        )
    }

    @Test
    fun `invalid config falls back to defaults`() {
        val file = tempDir.resolve(DragonFightConfig.CONFIG_FILE)
        Files.writeString(file, """{ "borderRadius": 0 }""")
        assertEquals(DragonFightConfig.DEFAULTS, DragonFightConfig.load(file))
    }

    @Test
    fun `settings are validated`() {
        assertThrows(IllegalArgumentException::class.java) {
            DragonFightConfig.Settings(10, 10, 0, 168)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DragonFightConfig.Settings(10, 10, 300, 0)
        }
    }
}
