package eu.mctraveler.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MapCommandTest {

    @Test
    fun `buildMapUrl formats coordinates to two decimal places`() {
        val url = buildMapUrl(-59.09, -182.83)
        assertEquals("https://map.mctraveler.eu/#x=-59.09&z=-182.83&zoom=1", url)
    }
}
