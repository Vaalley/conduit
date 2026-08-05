package eu.mctraveler.region

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RegionCommandsTest {

    @Test
    fun `parseLocateQuery defaults to page 1 when no trailing number`() {
        val (query, page) = RegionCommands.parseLocateQuery(
            "Vaalley",
            hasMatches = { it == "Vaalley" },
            maxPages = { 3 },
        )
        assertEquals("Vaalley", query)
        assertEquals(1, page)
    }

    @Test
    fun `parseLocateQuery extracts page number when valid`() {
        val (query, page) = RegionCommands.parseLocateQuery(
            "Vaalley 2",
            hasMatches = { it == "Vaalley" },
            maxPages = { 3 },
        )
        assertEquals("Vaalley", query)
        assertEquals(2, page)
    }

    @Test
    fun `parseLocateQuery falls back to full query when page number exceeds max pages and full query matches`() {
        val (query, page) = RegionCommands.parseLocateQuery(
            "zone 2",
            hasMatches = { it == "zone 2" },
            maxPages = { 1 },
        )
        assertEquals("zone 2", query)
        assertEquals(1, page)
    }

    @Test
    fun `parseLocateQuery trims whitespace`() {
        val (query, page) = RegionCommands.parseLocateQuery(
            "   my region   3  ",
            hasMatches = { it == "my region" },
            maxPages = { 5 },
        )
        assertEquals("my region", query)
        assertEquals(3, page)
    }
}
