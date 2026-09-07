package eu.mctraveler.worlds

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two decisions of the countdown that need no server: which tick says
 * which number, and how far is "moved". The rest — the sound, the teleport,
 * the cancellation — is proved against live players in `TeleportCountdownGameTest`.
 */
class TeleportCountdownTest {

    @Test
    fun `the count lasts three seconds of ticks`() {
        assertEquals(60, TeleportCountdown.DURATION_TICKS)
    }

    @Test
    fun `two and one are spoken on the second boundaries and three is left to begin`() {
        val spoken = (0 until TeleportCountdown.DURATION_TICKS)
            .mapNotNull { tick -> TeleportCountdown.announcementAt(tick)?.let { tick to it } }
        assertEquals(listOf(20 to 2, 40 to 1), spoken)
        assertNull(TeleportCountdown.announcementAt(0), "begin speaks the first number itself")
        assertNull(TeleportCountdown.announcementAt(TeleportCountdown.DURATION_TICKS), "the last tick departs, it does not count")
    }

    @Test
    fun `half a block of drift is tolerated and a step is not`() {
        val origin = Vec3(10.0, 64.0, -3.0)
        assertFalse(TeleportCountdown.hasMoved(origin, origin))
        assertFalse(TeleportCountdown.hasMoved(origin, origin.add(0.3, 0.0, 0.3)))
        assertTrue(TeleportCountdown.hasMoved(origin, origin.add(0.6, 0.0, 0.0)))
        assertTrue(TeleportCountdown.hasMoved(origin, origin.add(0.0, 1.0, 0.0)), "jumping counts as moving")
    }
}
