package eu.mctraveler.rtp

import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RtpCooldownTest {

    private val player = UUID.randomUUID()

    @AfterEach
    fun reset() = RtpCooldown.clear()

    @Test
    fun `a player nobody has seen may go at once`() {
        assertEquals(0, RtpCooldown.remaining(player, now = 12_345))
    }

    @Test
    fun `the wait counts down in ticks and bottoms out at zero`() {
        RtpCooldown.start(player, now = 100, durationTicks = 6000)
        assertEquals(6000, RtpCooldown.remaining(player, now = 100))
        assertEquals(1, RtpCooldown.remaining(player, now = 6099))
        assertEquals(0, RtpCooldown.remaining(player, now = 6100))
        assertEquals(0, RtpCooldown.remaining(player, now = 9999))
    }

    @Test
    fun `forgetting a player lets them go again`() {
        RtpCooldown.start(player, now = 0, durationTicks = 6000)
        RtpCooldown.forget(player)
        assertEquals(0, RtpCooldown.remaining(player, now = 1))
    }

    @Test
    fun `overworld and End waits are independent`() {
        RtpCooldown.start(player, now = 100, durationTicks = 6000)
        RtpCooldown.start(player, now = 100, durationTicks = 12_000, kind = RtpKind.END)

        assertEquals(6000, RtpCooldown.remaining(player, now = 100))
        assertEquals(12_000, RtpCooldown.remaining(player, now = 100, kind = RtpKind.END))
    }

    @Test
    fun `the wait is spelled out in whole seconds rounded up`() {
        assertEquals("1s", RtpCooldown.format(1))
        assertEquals("12s", RtpCooldown.format(12 * 20))
        assertEquals("5m", RtpCooldown.format(5 * 60 * 20))
        assertEquals("4m 12s", RtpCooldown.format((4 * 60 + 12) * 20 - 5))
    }
}
