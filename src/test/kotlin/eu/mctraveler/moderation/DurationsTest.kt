package eu.mctraveler.moderation

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DurationsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `duration table converts to milliseconds`() {
        assertEquals(30_000L, Durations.parse("30s"))
        assertEquals(600_000L, Durations.parse("10m"))
        assertEquals(7_200_000L, Durations.parse("2h"))
        assertEquals(604_800_000L, Durations.parse("7d"))
        assertEquals(1_209_600_000L, Durations.parse("2w"))
        assertNull(Durations.parse("perm"))
        assertNull(Durations.parse("permanent"))
    }

    @Test
    fun `expired punishments are no longer active`() {
        val store = PunishmentStore(dir.resolve("punishments.json"))
        val target = UUID.randomUUID()
        store.add(PunishmentType.BAN, target, "Alice", null, "Console", "test", System.currentTimeMillis() - 1)
        assertNull(store.activeBan(target))
        assertEquals(false, store.history(target).single().active)
    }
}
