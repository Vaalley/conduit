package eu.mctraveler.rtp

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RtpConfigTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a missing file is written with the defaults`() {
        val file = tempDir.resolve(RtpConfig.CONFIG_FILE)

        assertEquals(RtpConfig.DEFAULTS, RtpConfig.load(file))
        assertTrue(Files.exists(file))
        assertTrue(Files.readString(file).contains("\"radius\": 25000"))
    }

    @Test
    fun `the defaults are the agreed ring and wait`() {
        assertEquals(RtpConfig.Settings(radius = 25_000, minDistance = 1_000, cooldownSeconds = 300), RtpConfig.DEFAULTS)
        assertEquals(6000, RtpConfig.DEFAULTS.cooldownTicks)
    }

    @Test
    fun `a partial file keeps the defaults for what it leaves out`() {
        val file = tempDir.resolve(RtpConfig.CONFIG_FILE)
        Files.writeString(file, """{ "radius": 4000, "cooldownSeconds": 30 }""")

        assertEquals(RtpConfig.Settings(radius = 4000, minDistance = 1_000, cooldownSeconds = 30), RtpConfig.load(file))
    }

    @Test
    fun `a broken file falls back to the defaults rather than failing startup`() {
        val file = tempDir.resolve(RtpConfig.CONFIG_FILE)
        Files.writeString(file, """{ "radius": 100, "minDistance": 500 }""")

        assertEquals(RtpConfig.DEFAULTS, RtpConfig.load(file))
    }

    @Test
    fun `the ring must have room in it`() {
        assertThrows(IllegalArgumentException::class.java) { RtpConfig.Settings(radius = 100, minDistance = 100, cooldownSeconds = 0) }
        assertThrows(IllegalArgumentException::class.java) { RtpConfig.Settings(radius = 0, minDistance = 0, cooldownSeconds = 0) }
    }
}
