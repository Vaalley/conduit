package eu.mctraveler.geo

import java.io.Reader
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.Comparator
import java.util.zip.ZipFile

/** A country as GeoLite2 names it: `Canada`, `CA`. The ISO code is absent for a few entries. */
data class GeoCountry(
    val name: String,
    val isoCode: String?,
)

/**
 * MaxMind's GeoLite2 Country data in its CSV form, loaded into memory for
 * address → country lookups (docs/geoip.md). The three CSV members — the IPv4
 * and IPv6 block lists and the English locations list — carry ~650k networks
 * between them, so each table is stored as parallel primitive arrays sorted by
 * network start and searched by unsigned binary search, never as a row per
 * network.
 *
 * Columns are read by header name, not position: MaxMind adds columns over time
 * (`is_anycast` was the last) and a new one must not shift the data out from
 * under us. A row we cannot make sense of is counted and skipped rather than
 * failing the load; a header missing a column we need is fatal, because the
 * alternative is silently answering "unknown country" forever.
 */
class GeoLite2Country private constructor(
    private val ipv4: Ipv4Table,
    private val ipv6: Ipv6Table,
) {
    fun lookup(address: InetAddress): GeoCountry? {
        val bytes = address.address
        return when (bytes.size) {
            4 -> ipv4.lookup(bytes, 0)
            16 -> ipv4AddressOffset(bytes)?.let { ipv4.lookup(bytes, it) } ?: ipv6.lookup(bytes)
            else -> null
        }
    }

    companion object {
        const val IPV4_FILE = "GeoLite2-Country-Blocks-IPv4.csv"
        const val IPV6_FILE = "GeoLite2-Country-Blocks-IPv6.csv"
        const val LOCATIONS_FILE = "GeoLite2-Country-Locations-en.csv"

        fun load(
            blocksIpv4: Reader,
            blocksIpv6: Reader,
            locations: Reader,
            log: (String) -> Unit = {},
        ): GeoLite2Country {
            val stats = ParseStats()
            val locationsById = parseLocations(locations, stats)
            val ipv4 = parseIpv4Blocks(blocksIpv4, locationsById, stats)
            val ipv6 = parseIpv6Blocks(blocksIpv6, locationsById, stats)
            log(
                "GeoLite2 country database loaded: ${ipv4.size} IPv4 networks, " +
                    "${ipv6.size} IPv6 networks, ${stats.malformedRows} malformed rows skipped",
            )
            return GeoLite2Country(ipv4, ipv6)
        }

        fun loadZip(zipPath: Path, log: (String) -> Unit = {}): GeoLite2Country =
            ZipFile(zipPath.toFile()).use { zip ->
                val entries = listOf(IPV4_FILE, IPV6_FILE, LOCATIONS_FILE).map { name ->
                    zip.entries().asSequence()
                        .firstOrNull { entry ->
                            !entry.isDirectory && (entry.name == name || entry.name.endsWith("/$name"))
                        }
                        ?: throw IllegalArgumentException("GeoLite2 zip is missing $name")
                }
                zip.getInputStream(entries[0]).reader().use { ipv4 ->
                    zip.getInputStream(entries[1]).reader().use { ipv6 ->
                        zip.getInputStream(entries[2]).reader().use { locations ->
                            load(ipv4, ipv6, locations, log)
                        }
                    }
                }
            }
    }
}

private fun ipv4AddressOffset(bytes: ByteArray): Int? {
    val compatible = allZero(bytes, 12)
    val mapped = allZero(bytes, 10) &&
        bytes[10] == 0xff.toByte() &&
        bytes[11] == 0xff.toByte()
    return if (compatible || mapped) 12 else null
}

private fun allZero(bytes: ByteArray, length: Int): Boolean {
    for (index in 0 until length) {
        if (bytes[index] != 0.toByte()) return false
    }
    return true
}

private data class ParseStats(var malformedRows: Int = 0)

private data class ParsedLocations(
    val ids: Map<Int, Int>,
    val countries: List<GeoCountry>,
)

private fun parseLocations(reader: Reader, stats: ParseStats): ParsedLocations {
    val ids = HashMap<Int, Int>()
    val countries = ArrayList<GeoCountry>()
    Csv.read(reader, setOf("geoname_id", "country_name", "country_iso_code"), stats) { fields, columns ->
        val id = fields[columns["geoname_id"]!!].trim().toIntOrNull()
        val name = fields[columns["country_name"]!!].trim().takeIf(String::isNotBlank)
        if (id == null || name == null) {
            stats.malformedRows++
            return@read
        }
        ids[id] = countries.size
        countries += GeoCountry(
            name,
            fields[columns["country_iso_code"]!!].trim().takeIf(String::isNotBlank),
        )
    }
    return ParsedLocations(ids, countries)
}

private fun <N : Network, T : IpvTable> parseBlocks(
    reader: Reader,
    locations: ParsedLocations,
    stats: ParseStats,
    rows: IpvTableBuilder<N, T>,
    parseNetwork: (String) -> N?,
): T {
    Csv.read(
        reader,
        setOf("network", "geoname_id", "registered_country_geoname_id"),
        stats,
    ) { fields, columns ->
        val network = fields[columns["network"]!!].trim()
        val geoname = fields[columns["geoname_id"]!!].trim()
        val registeredCountry = fields[columns["registered_country_geoname_id"]!!].trim()
        val countryId = when {
            geoname.isNotBlank() -> geoname.toIntOrNull() ?: run {
                stats.malformedRows++
                return@read
            }

            registeredCountry.isNotBlank() -> registeredCountry.toIntOrNull() ?: run {
                stats.malformedRows++
                return@read
            }

            else -> return@read
        }
        val countryIndex = locations.ids[countryId]
        if (network.isBlank() || countryIndex == null) return@read
        val parsed = parseNetwork(network)
        if (parsed == null) {
            stats.malformedRows++
            return@read
        }
        rows.add(parsed, countryIndex)
    }
    return rows.build(locations.countries)
}

private fun parseIpv4Blocks(
    reader: Reader,
    locations: ParsedLocations,
    stats: ParseStats,
): Ipv4Table = parseBlocks(
    reader,
    locations,
    stats,
    Ipv4TableBuilder(),
    Network::parseIpv4,
)

private fun parseIpv6Blocks(
    reader: Reader,
    locations: ParsedLocations,
    stats: ParseStats,
): Ipv6Table = parseBlocks(
    reader,
    locations,
    stats,
    Ipv6TableBuilder(),
    Network::parseIpv6,
)

private sealed interface IpvTable {
    val size: Int
}

private class Ipv4Table(
    private val starts: IntArray,
    private val prefixes: ByteArray,
    private val countries: IntArray,
    private val countryValues: List<GeoCountry>,
) : IpvTable {
    override val size: Int get() = starts.size

    fun lookup(bytes: ByteArray, offset: Int): GeoCountry? {
        val value = ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
        var low = 0
        var high = starts.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (Integer.compareUnsigned(starts[middle], value) <= 0) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (found < 0) return null
        val prefix = prefixes[found].toInt()
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        if (value and mask != starts[found]) return null
        return countryValues[countries[found]]
    }
}

private class Ipv6Table(
    private val high: LongArray,
    private val low: LongArray,
    private val prefixes: ByteArray,
    private val countries: IntArray,
    private val countryValues: List<GeoCountry>,
) : IpvTable {
    override val size: Int get() = high.size

    fun lookup(bytes: ByteArray): GeoCountry? {
        val valueHigh = bytesToLong(bytes, 0)
        val valueLow = bytesToLong(bytes, 8)
        var left = 0
        var right = high.size - 1
        var found = -1
        while (left <= right) {
            val middle = (left + right) ushr 1
            val comparison = compareUnsigned(high[middle], low[middle], valueHigh, valueLow)
            if (comparison <= 0) {
                found = middle
                left = middle + 1
            } else {
                right = middle - 1
            }
        }
        if (found < 0) return null
        val prefix = prefixes[found].toInt()
        if (!contains(high[found], low[found], valueHigh, valueLow, prefix)) return null
        return countryValues[countries[found]]
    }
}

private class Ipv4TableBuilder : IpvTableBuilder<Network.V4, Ipv4Table> {
    override var size = 0
    private var starts = IntArray(1024)
    private var prefixes = ByteArray(1024)
    private var countryIds = IntArray(1024)

    override fun add(network: Network.V4, country: Int) {
        ensure(size + 1)
        starts[size] = network.start
        prefixes[size] = network.prefix.toByte()
        countryIds[size] = country
        size++
    }

    override fun build(countries: List<GeoCountry>): Ipv4Table {
        sortIpv4(starts, prefixes, countryIds, size - 1)
        return Ipv4Table(
            starts.copyOf(size),
            prefixes.copyOf(size),
            countryIds.copyOf(size),
            countries,
        )
    }

    private fun ensure(required: Int) {
        if (required <= starts.size) return
        val capacity = starts.size * 2
        starts = starts.copyOf(capacity)
        prefixes = prefixes.copyOf(capacity)
        countryIds = countryIds.copyOf(capacity)
    }
}

private class Ipv6TableBuilder : IpvTableBuilder<Network.V6, Ipv6Table> {
    override var size = 0
    private var highs = LongArray(1024)
    private var lows = LongArray(1024)
    private var prefixes = ByteArray(1024)
    private var countryIds = IntArray(1024)

    override fun add(network: Network.V6, country: Int) {
        ensure(size + 1)
        highs[size] = network.high
        lows[size] = network.low
        prefixes[size] = network.prefix.toByte()
        countryIds[size] = country
        size++
    }

    override fun build(countries: List<GeoCountry>): Ipv6Table {
        sortIpv6(highs, lows, prefixes, countryIds, size - 1)
        return Ipv6Table(
            highs.copyOf(size),
            lows.copyOf(size),
            prefixes.copyOf(size),
            countryIds.copyOf(size),
            countries,
        )
    }

    private fun ensure(required: Int) {
        if (required <= highs.size) return
        val capacity = highs.size * 2
        highs = highs.copyOf(capacity)
        lows = lows.copyOf(capacity)
        prefixes = prefixes.copyOf(capacity)
        countryIds = countryIds.copyOf(capacity)
    }
}

private interface IpvTableBuilder<N : Network, T : IpvTable> {
    var size: Int
    fun add(network: N, country: Int)
    fun build(countries: List<GeoCountry>): T
}

private sealed interface Network {
    val prefix: Int

    data class V4(val start: Int, override val prefix: Int) : Network
    data class V6(val high: Long, val low: Long, override val prefix: Int) : Network

    companion object {
        fun parseIpv4(value: String): V4? {
            val (address, prefix) = parseAddress(value, 32) ?: return null
            val bytes = address.address
            if (bytes.size != 4) return null
            val addressValue = ((bytes[0].toInt() and 0xff) shl 24) or
                ((bytes[1].toInt() and 0xff) shl 16) or
                ((bytes[2].toInt() and 0xff) shl 8) or
                (bytes[3].toInt() and 0xff)
            val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
            return V4(addressValue and mask, prefix)
        }

        fun parseIpv6(value: String): V6? {
            val (address, prefix) = parseAddress(value, 128) ?: return null
            val bytes = address.address
            if (bytes.size != 16) return null
            val high = bytesToLong(bytes, 0)
            val low = bytesToLong(bytes, 8)
            return V6(maskHigh(high, prefix), maskLow(low, prefix), prefix)
        }

        private fun parseAddress(value: String, maxPrefix: Int): Pair<InetAddress, Int>? {
            val slash = value.lastIndexOf('/')
            if (slash <= 0 || slash == value.lastIndex) return null
            val prefix = value.substring(slash + 1).toIntOrNull() ?: return null
            if (prefix !in 0..maxPrefix) return null
            val address = try {
                InetAddress.getByName(value.substring(0, slash))
            } catch (_: Exception) {
                return null
            }
            return address to prefix
        }
    }
}

private class Csv private constructor() {
    companion object {
        fun read(
            reader: Reader,
            required: Set<String>,
            stats: ParseStats,
            consume: (List<String>, Map<String, Int>) -> Unit,
        ) {
            val buffered = reader.buffered()
            val headerLine = buffered.readLine()
                ?: throw IllegalArgumentException(
                    "CSV header is missing required columns: ${required.joinToString(", ")}",
                )
            val header = parseLine(headerLine)
                ?: throw IllegalArgumentException("CSV header is malformed")
            val indexes = required.associateWith { header.indexOf(it) }
            val missing = indexes.filterValues { it < 0 }.keys
            if (missing.isNotEmpty()) {
                throw IllegalArgumentException(
                    "CSV header is missing required columns: ${missing.joinToString(", ")}",
                )
            }
            val maxIndex = indexes.values.max()
            while (true) {
                val line = buffered.readLine() ?: break
                val fields = parseLine(line)
                if (fields == null || fields.size <= maxIndex) {
                    stats.malformedRows++
                    continue
                }
                consume(fields, indexes)
            }
        }

        private fun parseLine(line: String): List<String>? {
            val fields = ArrayList<String>()
            val field = StringBuilder()
            var quoted = false
            var index = 0
            while (index < line.length) {
                val character = line[index++]
                when {
                    character == '"' && quoted && index < line.length && line[index] == '"' -> {
                        field.append('"')
                        index++
                    }

                    character == '"' -> quoted = !quoted
                    character == ',' && !quoted -> {
                        fields += field.toString()
                        field.clear()
                    }

                    else -> field.append(character)
                }
            }
            if (quoted) return null
            fields += field.toString()
            return fields
        }
    }
}

private fun Reader.buffered() = if (this is java.io.BufferedReader) this else java.io.BufferedReader(this)

private fun bytesToLong(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xff) shl 56) or
        ((bytes[offset + 1].toLong() and 0xff) shl 48) or
        ((bytes[offset + 2].toLong() and 0xff) shl 40) or
        ((bytes[offset + 3].toLong() and 0xff) shl 32) or
        ((bytes[offset + 4].toLong() and 0xff) shl 24) or
        ((bytes[offset + 5].toLong() and 0xff) shl 16) or
        ((bytes[offset + 6].toLong() and 0xff) shl 8) or
        (bytes[offset + 7].toLong() and 0xff)

private fun maskHigh(value: Long, prefix: Int): Long = when {
    prefix == 0 -> 0
    prefix >= 64 -> value
    else -> value and (-1L shl (64 - prefix))
}

private fun maskLow(value: Long, prefix: Int): Long = when {
    prefix <= 64 -> 0
    else -> value and (-1L shl (128 - prefix))
}

private fun contains(startHigh: Long, startLow: Long, valueHigh: Long, valueLow: Long, prefix: Int): Boolean {
    if (prefix == 0) return true
    if (prefix <= 64) return maskHigh(valueHigh, prefix) == startHigh
    return valueHigh == startHigh && maskLow(valueLow, prefix) == startLow
}

private fun compareUnsigned(firstHigh: Long, firstLow: Long, secondHigh: Long, secondLow: Long): Int {
    val highComparison = java.lang.Long.compareUnsigned(firstHigh, secondHigh)
    return if (highComparison != 0) highComparison else java.lang.Long.compareUnsigned(firstLow, secondLow)
}

private fun sortIpv4(starts: IntArray, prefixes: ByteArray, countries: IntArray, last: Int) {
    if (last < 1) return
    val order = Array(last + 1) { it }
    Arrays.sort(order, Comparator { first, second ->
        Integer.compareUnsigned(starts[first], starts[second])
    })
    val sortedStarts = IntArray(last + 1)
    val sortedPrefixes = ByteArray(last + 1)
    val sortedCountries = IntArray(last + 1)
    order.forEachIndexed { index, source ->
        sortedStarts[index] = starts[source]
        sortedPrefixes[index] = prefixes[source]
        sortedCountries[index] = countries[source]
    }
    sortedStarts.copyInto(starts)
    sortedPrefixes.copyInto(prefixes)
    sortedCountries.copyInto(countries)
}

private fun sortIpv6(highs: LongArray, lows: LongArray, prefixes: ByteArray, countries: IntArray, last: Int) {
    if (last < 1) return
    val order = Array(last + 1) { it }
    Arrays.sort(order, Comparator { first, second ->
        compareUnsigned(highs[first], lows[first], highs[second], lows[second])
    })
    val sortedHighs = LongArray(last + 1)
    val sortedLows = LongArray(last + 1)
    val sortedPrefixes = ByteArray(last + 1)
    val sortedCountries = IntArray(last + 1)
    order.forEachIndexed { index, source ->
        sortedHighs[index] = highs[source]
        sortedLows[index] = lows[source]
        sortedPrefixes[index] = prefixes[source]
        sortedCountries[index] = countries[source]
    }
    sortedHighs.copyInto(highs)
    sortedLows.copyInto(lows)
    sortedPrefixes.copyInto(prefixes)
    sortedCountries.copyInto(countries)
}
