package eu.mctraveler.tablist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The footer's TPS is the server's real TPS (deviation 4), derived from the average tick
 * time: `min(20, 1000 / mspt)`. Pure math, so it gets the unit tier; the tab list's
 * player-visible behaviour is covered by TabListGameTest.
 */
class TabListFeatureTest {

    @Test
    fun `a server ticking at the 50ms budget runs at 20 TPS`() {
        assertEquals(20.0, TabListFeature.tps(50_000_000))
    }

    @Test
    fun `a server averaging 100ms per tick runs at 10 TPS`() {
        assertEquals(10.0, TabListFeature.tps(100_000_000))
    }

    @Test
    fun `a server averaging 62_5ms per tick runs at 16 TPS`() {
        assertEquals(16.0, TabListFeature.tps(62_500_000))
    }

    @Test
    fun `ticking faster than the budget is capped at 20 TPS`() {
        assertEquals(20.0, TabListFeature.tps(40_000_000))
    }

    @Test
    fun `no tick samples yet reads as a healthy 20 TPS`() {
        assertEquals(20.0, TabListFeature.tps(0))
    }

    @Test
    fun `the footer renders TPS to one decimal`() {
        assertEquals(
            "\n          play.mctraveler.eu          \nTPS: 12.3",
            TabListFeature.footer(12.34).string,
        )
    }
}
