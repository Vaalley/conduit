package eu.mctraveler.embassy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The plot spiral: which chunk an embassy is offered next, and in what order.
 *
 * Allocation itself needs a live region tree, so it is a gametest; the walk is
 * pure arithmetic and lives here. The expected order is Nucleus's
 * `getPlotCoordsAtIndex` traced by hand — the plots on the dedi were handed out
 * in it, so ticket 05's imported embassies must land on the same chunks.
 */
class EmbassyPlotsTest {

    @Test
    fun `the spiral starts at the origin and lengthens its legs the way Nucleus did`() {
        val expected = listOf(
            0 to 0,
            1 to 0,
            1 to 1,
            0 to 1,
            -1 to 1,
            -1 to 0,
            -1 to -1,
            0 to -1,
            1 to -1,
            2 to -1,
            2 to 0,
            2 to 1,
            2 to 2,
            1 to 2,
        )
        val actual = EmbassyPlots.spiral().take(expected.size).map { it.x to it.z }.toList()
        assertEquals(expected, actual)
    }

    @Test
    fun `the spiral never offers the same chunk twice`() {
        val plots = EmbassyPlots.spiral().take(400).toList()
        assertEquals(400, plots.map { it.x to it.z }.toSet().size)
    }

    @Test
    fun `the spiral fills each ring before moving outward`() {
        // Ring n is the square of side 2n+1, so the first (2n+1)^2 plots are
        // exactly the chunks within Chebyshev distance n of the origin.
        val plots = EmbassyPlots.spiral().take(49).toList()
        for (ring in 0..3) {
            val count = (2 * ring + 1) * (2 * ring + 1)
            assertTrue(
                plots.take(count).all { maxOf(kotlin.math.abs(it.x), kotlin.math.abs(it.z)) <= ring },
                "the first $count plots should all be within ring $ring",
            )
        }
    }
}
