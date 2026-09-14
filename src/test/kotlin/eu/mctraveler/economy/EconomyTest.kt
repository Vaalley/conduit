package eu.mctraveler.economy

import eu.mctraveler.persistence.JsonPlayerStore
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EconomyTest {
    @TempDir
    lateinit var dir: Path

    private val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    @Test
    fun `withdrawal below balance succeeds and insufficient withdrawal writes nothing`() {
        val store = JsonPlayerStore(dir)
        Economy.deposit(store, uuid, 40)
        assertTrue(Economy.withdraw(store, uuid, 10))
        assertEquals(30L, Economy.balanceOf(store, uuid))
        val before = java.nio.file.Files.readString(dir.resolve("$uuid.json"))
        assertFalse(Economy.withdraw(store, uuid, 31))
        assertEquals(before, java.nio.file.Files.readString(dir.resolve("$uuid.json")))
        assertEquals(30L, Economy.balanceOf(store, uuid))
    }
}
