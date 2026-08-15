package eu.mctraveler.crystal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two decisions in the teleport-request flow that need no server: how long
 * a request stands (spec story 35, deviation 4), and what counts as the hidden
 * accept command (deviation 8).
 *
 * Everything else about requests is about two live players and is proved in
 * `CrystalMenuGameTest`.
 */
class CrystalRequestsTest {

    @Test
    fun `a request stands for five minutes of ticks`() {
        // Nucleus's 300 000 wall-clock milliseconds, in the house's ticks.
        assertTrue(CrystalRequests.TIMEOUT_TICKS == 5 * 60 * 20)
    }

    @Test
    fun `a request lapses only once it is older than the timeout`() {
        val made = 1_000
        assertFalse(CrystalRequests.hasTimedOut(made, made), "a request made this tick has not lapsed")
        assertFalse(
            CrystalRequests.hasTimedOut(made, made + CrystalRequests.TIMEOUT_TICKS),
            "a request exactly at the timeout is still good (Nucleus compared strictly)",
        )
        assertTrue(
            CrystalRequests.hasTimedOut(made, made + CrystalRequests.TIMEOUT_TICKS + 1),
            "a request one tick past the timeout has lapsed",
        )
    }

    @Test
    fun `the accept command is recognised with or without its slash`() {
        for (line in listOf(
            "teleportation-crystal-accept",
            "/teleportation-crystal-accept",
            "teleportation-crystal-accept Someone",
            "/teleportation-crystal-accept Someone",
        )) {
            assertTrue(CrystalRequests.isAcceptCommand(line), "\"$line\" should be the accept command")
        }
    }

    @Test
    fun `no other command is mistaken for it`() {
        for (line in listOf(
            "",
            "teleport",
            "tell Someone hi",
            // The prefix test must not swallow a longer command that merely
            // starts the same way.
            "teleportation-crystal-accept-all Someone",
            "say teleportation-crystal-accept",
        )) {
            assertFalse(CrystalRequests.isAcceptCommand(line), "\"$line\" is not the accept command")
        }
    }
}
