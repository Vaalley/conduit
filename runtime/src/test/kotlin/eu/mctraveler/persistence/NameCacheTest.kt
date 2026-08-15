package eu.mctraveler.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Unit tier for the Persistence service's uuid → username cache: the real name
 * cache that replaces the Portal's op-only uuid cache, keeping the Portal's
 * `uuid-cache.json` file format so migrated data slots straight in.
 */
class NameCacheTest {
    @TempDir
    lateinit var dir: Path

    private val notch = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private val jeb = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6")

    private fun cacheFile(): Path = dir.resolve("uuid-cache.json")

    private fun cache(): NameCache = NameCache(cacheFile())

    @Test
    fun `an unknown uuid has no username`() {
        assertNull(cache().usernameFor(notch))
    }

    @Test
    fun `a recorded login answers offline lookups`() {
        cache().record(notch, "Notch")
        assertEquals("Notch", cache().usernameFor(notch))
    }

    @Test
    fun `recording again after a name change updates the username`() {
        val cache = cache()
        cache.record(notch, "Notch")
        cache.record(notch, "NotchRenamed")
        assertEquals("NotchRenamed", cache.usernameFor(notch))
    }

    @Test
    fun `recorded names survive a restart`() {
        cache().record(notch, "Notch")
        cache().record(jeb, "jeb_")
        // A fresh instance over the same file — the restart seam.
        val restarted = cache()
        assertEquals("Notch", restarted.usernameFor(notch))
        assertEquals("jeb_", restarted.usernameFor(jeb))
    }

    @Test
    fun `answers from a Portal-written uuid-cache file`() {
        // The Portal's uuid-cache.json format: one object, uuid keys, name values.
        Files.writeString(
            cacheFile(),
            """{"069a79f4-44e9-4726-a5be-fca90e38aaf5":"Notch","853c80ef-3c37-49fd-aa49-938b674adae6":"jeb_"}""",
        )
        assertEquals("Notch", cache().usernameFor(notch))
        assertEquals("jeb_", cache().usernameFor(jeb))
    }

    @Test
    fun `recording into a Portal-written file keeps the other entries`() {
        Files.writeString(cacheFile(), """{"069a79f4-44e9-4726-a5be-fca90e38aaf5":"Notch"}""")
        cache().record(jeb, "jeb_")
        assertEquals(
            """{"069a79f4-44e9-4726-a5be-fca90e38aaf5":"Notch","853c80ef-3c37-49fd-aa49-938b674adae6":"jeb_"}""",
            Files.readString(cacheFile()),
        )
    }
}
