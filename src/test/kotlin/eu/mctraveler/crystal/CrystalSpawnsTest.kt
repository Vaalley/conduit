package eu.mctraveler.crystal

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CrystalSpawnsTest {

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun `missing config writes the two default spawns`() {
        val file = tempDir.resolve(CrystalSpawns.CONFIG_FILE)

        val loaded = CrystalSpawns.load(file)

        assertEquals(CrystalSpawns.DEFAULTS, loaded)
        assertTrue(Files.exists(file))
        assertTrue(Files.readString(file).contains("\"spawns\""))
    }

    @Test
    fun `an extra configured spawn is retained for future menu and command entries`() {
        val file = tempDir.resolve(CrystalSpawns.CONFIG_FILE)
        Files.writeString(
            file,
            """
            {
              "spawns": [
                {"name": "spawn 1", "x": 16.5, "y": 71.0, "z": -15.5, "yaw": 180},
                {"name": "spawn 2", "x": 0.5, "y": 67.5, "z": 802816.5, "yaw": 0},
                {"name": "spawn 3", "x": 4.5, "y": 70.0, "z": 9.5, "yaw": 90}
              ]
            }
            """.trimIndent(),
        )

        val loaded = CrystalSpawns.load(file)

        assertEquals(3, loaded.size)
        assertEquals("spawn 3", loaded[2].name)
        assertEquals(4.5, loaded[2].x)
        assertEquals(70.0, loaded[2].y)
        assertEquals(9.5, loaded[2].z)
        assertEquals(90.0f, loaded[2].yaw)
        assertEquals("spawn3", CrystalSpawns.commandName(2))
    }
}
