package eu.mctraveler.geo

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GeoLite2CountryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `parses reordered and extra columns for ipv4 and ipv6`() {
        val database = database(
            locations = """
                country_iso_code,extra,country_name,geoname_id
                CA,ignored,Canada,100
            """.trimIndent(),
            ipv4 = """
                registered_country_geoname_id,network,geoname_id,is_anycast
                ,192.0.2.0/30,100,false
            """.trimIndent(),
            ipv6 = """
                network,country_iso_code,registered_country_geoname_id,geoname_id
                2001:db8::/126,CA,,100
            """.trimIndent(),
        )

        assertEquals("Canada", database.lookup(address("192.0.2.0"))?.name)
        assertEquals("Canada", database.lookup(address("192.0.2.3"))?.name)
        assertEquals("Canada", database.lookup(address("2001:db8::"))?.name)
        assertEquals("Canada", database.lookup(address("2001:db8::3"))?.name)
    }

    @Test
    fun `falls back to registered country when geoname is empty`() {
        val database = database(
            locations = """
                geoname_id,country_name,country_iso_code
                100,Canada,CA
            """.trimIndent(),
            ipv4 = """
                network,geoname_id,registered_country_geoname_id
                198.51.100.0/24,,100
            """.trimIndent(),
        )

        assertEquals("Canada", database.lookup(address("198.51.100.1"))?.name)
    }

    @Test
    fun `returns null for an address outside every network`() {
        val database = database(
            ipv4 = "network,geoname_id,registered_country_geoname_id\n203.0.113.0/31,100,",
        )

        assertNull(database.lookup(address("203.0.113.2")))
    }

    @Test
    fun `looks up ipv4 mapped and compatible ipv6 addresses in the ipv4 table`() {
        val database = database(
            ipv4 = """
                network,geoname_id,registered_country_geoname_id
                203.0.113.0/24,100,
            """.trimIndent(),
            ipv6 = "network,geoname_id,registered_country_geoname_id",
        )

        assertEquals("Canada", database.lookup(address("::ffff:203.0.113.5"))?.name)
        assertEquals("Canada", database.lookup(address("::203.0.113.5"))?.name)
    }

    @Test
    fun `rejects a csv header missing a required column`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            GeoLite2Country.load(
                blocksIpv4 = "network,geoname_id\n203.0.113.0/24,100".reader(),
                blocksIpv6 = IPV6.trimIndent().reader(),
                locations = LOCATIONS.trimIndent().reader(),
            )
        }

        assertEquals(
            "CSV header is missing required columns: registered_country_geoname_id",
            failure.message,
        )
    }

    @Test
    fun `skips malformed rows without throwing`() {
        val summaries = mutableListOf<String>()
        val database = GeoLite2Country.load(
            blocksIpv4 = "network,geoname_id,registered_country_geoname_id\nbad\n203.0.113.0/24,100,".reader(),
            blocksIpv6 = "network,geoname_id,registered_country_geoname_id\n".reader(),
            locations = "geoname_id,country_name,country_iso_code\n100,Canada,CA".reader(),
            log = summaries::add,
        )

        assertEquals("Canada", database.lookup(address("203.0.113.1"))?.name)
        assertEquals(1, summaries.size)
        assertEquals(true, summaries.single().contains("1 malformed rows skipped"))
    }

    @Test
    fun `loads csv members from a dated zip directory`() {
        val zipPath = temporaryDirectory.resolve("GeoLite2-Country-CSV.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            zip.writeMember("GeoLite2-Country_2025/GeoLite2-Country-Locations-en.csv", LOCATIONS.trimIndent())
            zip.writeMember("GeoLite2-Country_2025/GeoLite2-Country-Blocks-IPv4.csv", IPV4.trimIndent())
            zip.writeMember("GeoLite2-Country_2025/GeoLite2-Country-Blocks-IPv6.csv", IPV6.trimIndent())
        }

        val database = GeoLite2Country.loadZip(zipPath)

        assertEquals("Canada", database.lookup(address("192.0.2.1"))?.name)
        assertEquals("Canada", database.lookup(address("2001:db8::1"))?.name)
    }

    private fun database(
        locations: String = LOCATIONS,
        ipv4: String = IPV4,
        ipv6: String = IPV6,
    ): GeoLite2Country = GeoLite2Country.load(
        ipv4.trimIndent().reader(),
        ipv6.trimIndent().reader(),
        locations.trimIndent().reader(),
    )

    private fun address(value: String): InetAddress = InetAddress.getByName(value)

    private fun ZipOutputStream.writeMember(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private companion object {
        const val LOCATIONS = """
            geoname_id,country_name,country_iso_code
            100,Canada,CA
        """
        const val IPV4 = """
            network,geoname_id,registered_country_geoname_id
            192.0.2.0/30,100,
        """
        const val IPV6 = """
            network,geoname_id,registered_country_geoname_id
            2001:db8::/126,100,
        """
    }
}
