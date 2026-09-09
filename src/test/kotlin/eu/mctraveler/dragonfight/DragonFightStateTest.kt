package eu.mctraveler.dragonfight

import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DragonFightStateTest {
    @TempDir
    lateinit var tempDir: Path

    private val owner = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val guest = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun `completed players and arena records round trip`() {
        val file = tempDir.resolve("dragonfight-state.json")
        val state = DragonFightState(file)
        state.markCompleted(owner)
        state.putArena(owner, DragonFightState.ArenaRecord(1234L, setOf(guest)))

        val loaded = DragonFightState(file)
        assertTrue(loaded.isCompleted(owner))
        assertEquals(DragonFightState.ArenaRecord(1234L, setOf(guest)), loaded.arena(owner))
        assertEquals(setOf(owner), loaded.arenas().keys)
    }

    @Test
    fun `mutations remove completion and arenas`() {
        val state = DragonFightState(tempDir.resolve("dragonfight-state.json"))
        state.markCompleted(owner)
        state.putArena(owner, DragonFightState.ArenaRecord(1L, emptySet()))
        state.forgetCompleted(owner)
        state.removeArena(owner)
        assertFalse(state.isCompleted(owner))
        assertTrue(state.arenas().isEmpty())
    }
}
