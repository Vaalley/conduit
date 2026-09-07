package eu.mctraveler.geo

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DbIpCountryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `looks up inclusive ipv4 range bounds and returns null in a gap`() {
        val database = database(
            """
            192.0.2.0,192.0.2.3,CA
            192.0.2.8,192.0.2.11,AU
            """.trimIndent(),
        )

        assertEquals("Canada", database.lookup(address("192.0.2.0"))?.name)
        assertEquals("Canada", database.lookup(address("192.0.2.3"))?.name)
        assertNull(database.lookup(address("192.0.2.4")))
        assertEquals("Australia", database.lookup(address("192.0.2.11"))?.name)
    }

    @Test
    fun `looks up ipv6 ranges`() {
        val database = database(
            """
            2001:db8::,2001:db8::3,CA
            2001:db8::8,2001:db8::b,AU
            """.trimIndent(),
        )

        assertEquals("Canada", database.lookup(address("2001:db8::"))?.name)
        assertEquals("Canada", database.lookup(address("2001:db8::3"))?.name)
        assertNull(database.lookup(address("2001:db8::4")))
        assertEquals("Australia", database.lookup(address("2001:db8::b"))?.name)
    }

    @Test
    fun `skips unknown country rows`() {
        val database = database(
            """
            192.0.2.0,192.0.2.3,ZZ
            192.0.2.8,192.0.2.11,CA
            """.trimIndent(),
        )

        assertNull(database.lookup(address("192.0.2.1")))
        assertEquals("Canada", database.lookup(address("192.0.2.9"))?.name)
    }

    @Test
    fun `skips malformed short and blank rows and logs one summary`() {
        val summaries = mutableListOf<String>()
        val database = DbIpCountry.load(
            """
            192.0.2.0,192.0.2.3,CA
            malformed
            192.0.2.4,192.0.2.7

            """.trimIndent().reader(),
            summaries::add,
        )

        assertEquals("Canada", database.lookup(address("192.0.2.1"))?.name)
        assertEquals(1, summaries.size)
        assertTrue(summaries.single().contains("2 malformed rows skipped"))
    }

    @Test
    fun `throws when no usable rows remain`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            DbIpCountry.load("192.0.2.0,192.0.2.3,ZZ".reader())
        }

        assertEquals("DB-IP country data contains no usable ranges", failure.message)
    }

    @Test
    fun `loads plain csv path`() {
        val path = temporaryDirectory.resolve("dbip-country-lite-2026-08.csv")
        Files.writeString(path, "192.0.2.0,192.0.2.3,CA\n")

        assertEquals("Canada", DbIpCountry.load(path).lookup(address("192.0.2.1"))?.name)
    }

    @Test
    fun `loads gzip csv path`() {
        val path = temporaryDirectory.resolve("dbip-country-lite-2026-08.csv.gz")
        GZIPOutputStream(Files.newOutputStream(path)).bufferedWriter().use { writer ->
            writer.write("192.0.2.0,192.0.2.3,CA\n")
        }

        assertEquals("Canada", DbIpCountry.load(path).lookup(address("192.0.2.1"))?.name)
    }

    @Test
    fun `looks up mapped and compatible ipv6 addresses through ipv4 table`() {
        val database = database("203.0.113.0,203.0.113.255,CA")

        assertEquals("Canada", database.lookup(address("::ffff:203.0.113.5"))?.name)
        assertEquals("Canada", database.lookup(address("::203.0.113.5"))?.name)
    }

    @Test
    fun `retains XK when locale has no country display name`() {
        val country = database("192.0.2.0,192.0.2.3,XK")
            .lookup(address("192.0.2.1"))
            ?: error("expected an XK country")

        assertNotNull(country)
        assertEquals("XK", country.isoCode)
        assertTrue(country.name.isNotBlank())
    }

    private fun database(content: String): DbIpCountry =
        DbIpCountry.load(content.reader())

    private fun address(value: String): InetAddress = InetAddress.getByName(value)
}
