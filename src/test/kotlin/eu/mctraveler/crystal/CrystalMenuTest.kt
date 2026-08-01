package eu.mctraveler.crystal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The head GUI's shape (spec story 33): rows = ceil(others / 9).
 *
 * The rest of the menu needs a running server and lives in
 * `CrystalMenuGameTest`; this is the arithmetic, which does not.
 */
class CrystalMenuTest {

    @Test
    fun `a row holds nine heads, and a partial row still counts`() {
        val cases = mapOf(
            1 to 1,
            8 to 1,
            9 to 1,
            10 to 2,
            17 to 2,
            18 to 2,
            19 to 3,
            53 to 6,
            54 to 6,
        )
        for ((others, rows) in cases) {
            assertEquals(rows, CrystalMenu.rowsFor(others), "$others others should need $rows rows")
        }
    }

    @Test
    fun `a menu is never shorter than one row`() {
        // Unreachable through the menu itself — being alone is refused before
        // this is asked — but a zero-row chest screen does not exist.
        assertEquals(1, CrystalMenu.rowsFor(0))
    }

    @Test
    fun `a menu is never taller than a chest screen`() {
        // Nucleus's row count was unbounded; past 54 other players that asks
        // for a screen the protocol has no room for.
        for (others in listOf(55, 100, 1_000)) {
            assertEquals(
                CrystalMenu.MAX_ROWS,
                CrystalMenu.rowsFor(others),
                "$others others should still fit a six-row screen",
            )
        }
    }
}
