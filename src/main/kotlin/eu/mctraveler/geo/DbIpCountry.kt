package eu.mctraveler.geo

import java.io.Reader
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.Comparator
import java.util.Locale
import java.util.zip.GZIPInputStream

/** A country identified by its display name and ISO-3166 alpha-2 code. */
data class GeoCountry(
    val name: String,
    val isoCode: String?,
)

/**
 * DB-IP's IP-to-Country Lite data, loaded into compact range tables.
 *
 * The source is a headerless three-column CSV containing both IPv4 and IPv6
 * ranges. Final storage uses primitive parallel arrays, while parsing remains
 * available through a small Reader-based interface for deterministic tests.
 */
class DbIpCountry private constructor(
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
        fun load(reader: Reader, log: (String) -> Unit = {}): DbIpCountry {
            var malformedRows = 0
            val countries = ArrayList<GeoCountry>()
            val countryIndexes = HashMap<String, Int>()
            val ipv4Rows = Ipv4TableBuilder()
            val ipv6Rows = Ipv6TableBuilder()

            Csv.read(reader) { line ->
                if (line.isBlank()) return@read
                val fields = Csv.fields(line)
                if (fields == null) {
                    malformedRows++
                    return@read
                }
                val code = fields[2].trim().uppercase(Locale.ROOT)
                if (code == "ZZ") return@read
                if (code.length != 2 || code.any { it !in 'A'..'Z' }) {
                    malformedRows++
                    return@read
                }
                val start = fields[0].trim()
                val end = fields[1].trim()
                val country = countryIndexes.getOrPut(code) {
                    countries += countryFor(code)
                    countries.lastIndex
                }
                val startIpv4 = parseIpv4(start)
                val endIpv4 = parseIpv4(end)
                if (startIpv4 != null || endIpv4 != null) {
                    if (startIpv4 == null || endIpv4 == null ||
                        Integer.compareUnsigned(startIpv4, endIpv4) > 0
                    ) {
                        malformedRows++
                    } else {
                        ipv4Rows.add(startIpv4, endIpv4, country)
                    }
                    return@read
                }

                val startIpv6 = parseIpv6(start)
                val endIpv6 = parseIpv6(end)
                if (startIpv6 == null || endIpv6 == null ||
                    compareUnsigned(startIpv6.high, startIpv6.low, endIpv6.high, endIpv6.low) > 0
                ) {
                    malformedRows++
                } else {
                    ipv6Rows.add(
                        startIpv6.high,
                        startIpv6.low,
                        endIpv6.high,
                        endIpv6.low,
                        country,
                    )
                }
            }

            if (ipv4Rows.size == 0 && ipv6Rows.size == 0) {
                throw IllegalArgumentException("DB-IP country data contains no usable ranges")
            }
            val ipv4 = ipv4Rows.build(countries)
            val ipv6 = ipv6Rows.build(countries)
            log(
                "DB-IP country data loaded: ${ipv4.size} IPv4 ranges, " +
                    "${ipv6.size} IPv6 ranges, $malformedRows malformed rows skipped",
            )
            return DbIpCountry(ipv4, ipv6)
        }

        fun load(path: Path, log: (String) -> Unit = {}): DbIpCountry =
            Files.newInputStream(path).use { raw ->
                val input = if (path.toString().endsWith(".gz", ignoreCase = true)) {
                    GZIPInputStream(raw)
                } else {
                    raw
                }
                input.use { stream ->
                    stream.reader().use { reader -> load(reader, log) }
                }
            }
    }
}

private fun countryFor(code: String): GeoCountry {
    val display = try {
        Locale.of("", code).getDisplayCountry(Locale.ENGLISH)
    } catch (_: IllegalArgumentException) {
        ""
    }
    return GeoCountry(
        display.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code,
        code,
    )
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

private fun parseIpv4(value: String): Int? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    var address = 0
    for (part in parts) {
        if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        address = (address shl 8) or octet
    }
    return address
}

private data class Ipv6Address(val high: Long, val low: Long)

private fun parseIpv6(value: String): Ipv6Address? {
    if (!value.contains(':')) return null
    val bytes = try {
        InetAddress.getByName(value).address
    } catch (_: Exception) {
        return null
    }
    if (bytes.size != 16) return null
    return Ipv6Address(bytesToLong(bytes, 0), bytesToLong(bytes, 8))
}

private class Ipv4Table(
    private val starts: IntArray,
    private val ends: IntArray,
    private val countries: IntArray,
    private val countryValues: List<GeoCountry>,
) {
    val size: Int get() = starts.size

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
        if (found < 0 || Integer.compareUnsigned(value, ends[found]) > 0) return null
        return countryValues[countries[found]]
    }
}

private class Ipv6Table(
    private val startHigh: LongArray,
    private val startLow: LongArray,
    private val endHigh: LongArray,
    private val endLow: LongArray,
    private val countries: IntArray,
    private val countryValues: List<GeoCountry>,
) {
    val size: Int get() = startHigh.size

    fun lookup(bytes: ByteArray): GeoCountry? {
        val valueHigh = bytesToLong(bytes, 0)
        val valueLow = bytesToLong(bytes, 8)
        var left = 0
        var right = startHigh.size - 1
        var found = -1
        while (left <= right) {
            val middle = (left + right) ushr 1
            if (compareUnsigned(startHigh[middle], startLow[middle], valueHigh, valueLow) <= 0) {
                found = middle
                left = middle + 1
            } else {
                right = middle - 1
            }
        }
        if (found < 0 ||
            compareUnsigned(valueHigh, valueLow, endHigh[found], endLow[found]) > 0
        ) {
            return null
        }
        return countryValues[countries[found]]
    }
}

private class Ipv4TableBuilder {
    var size = 0
        private set
    private var starts = IntArray(1024)
    private var ends = IntArray(1024)
    private var countries = IntArray(1024)

    fun add(start: Int, end: Int, country: Int) {
        ensure(size + 1)
        starts[size] = start
        ends[size] = end
        countries[size] = country
        size++
    }

    fun build(countryValues: List<GeoCountry>): Ipv4Table {
        sortIpv4(starts, ends, countries, size - 1)
        return Ipv4Table(
            starts.copyOf(size),
            ends.copyOf(size),
            countries.copyOf(size),
            countryValues,
        )
    }

    private fun ensure(required: Int) {
        if (required <= starts.size) return
        val capacity = starts.size * 2
        starts = starts.copyOf(capacity)
        ends = ends.copyOf(capacity)
        countries = countries.copyOf(capacity)
    }
}

private class Ipv6TableBuilder {
    var size = 0
        private set
    private var startHigh = LongArray(1024)
    private var startLow = LongArray(1024)
    private var endHigh = LongArray(1024)
    private var endLow = LongArray(1024)
    private var countries = IntArray(1024)

    fun add(
        startHighValue: Long,
        startLowValue: Long,
        endHighValue: Long,
        endLowValue: Long,
        country: Int,
    ) {
        ensure(size + 1)
        startHigh[size] = startHighValue
        startLow[size] = startLowValue
        endHigh[size] = endHighValue
        endLow[size] = endLowValue
        countries[size] = country
        size++
    }

    fun build(countryValues: List<GeoCountry>): Ipv6Table {
        sortIpv6(startHigh, startLow, endHigh, endLow, countries, size - 1)
        return Ipv6Table(
            startHigh.copyOf(size),
            startLow.copyOf(size),
            endHigh.copyOf(size),
            endLow.copyOf(size),
            countries.copyOf(size),
            countryValues,
        )
    }

    private fun ensure(required: Int) {
        if (required <= startHigh.size) return
        val capacity = startHigh.size * 2
        startHigh = startHigh.copyOf(capacity)
        startLow = startLow.copyOf(capacity)
        endHigh = endHigh.copyOf(capacity)
        endLow = endLow.copyOf(capacity)
        countries = countries.copyOf(capacity)
    }
}

private class Csv private constructor() {
    companion object {
        fun read(reader: Reader, consume: (String) -> Unit) {
            val buffered = if (reader is java.io.BufferedReader) reader else java.io.BufferedReader(reader)
            while (true) {
                val line = buffered.readLine() ?: return
                consume(line)
            }
        }

        fun fields(line: String): Array<String>? {
            val first = line.indexOf(',')
            val second = if (first >= 0) line.indexOf(',', first + 1) else -1
            if (first <= 0 || second <= first + 1 || second == line.lastIndex ||
                line.indexOf(',', second + 1) >= 0
            ) {
                return null
            }
            return arrayOf(
                line.substring(0, first),
                line.substring(first + 1, second),
                line.substring(second + 1),
            )
        }
    }
}

private fun bytesToLong(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xff) shl 56) or
        ((bytes[offset + 1].toLong() and 0xff) shl 48) or
        ((bytes[offset + 2].toLong() and 0xff) shl 40) or
        ((bytes[offset + 3].toLong() and 0xff) shl 32) or
        ((bytes[offset + 4].toLong() and 0xff) shl 24) or
        ((bytes[offset + 5].toLong() and 0xff) shl 16) or
        ((bytes[offset + 6].toLong() and 0xff) shl 8) or
        (bytes[offset + 7].toLong() and 0xff)

private fun compareUnsigned(firstHigh: Long, firstLow: Long, secondHigh: Long, secondLow: Long): Int {
    val highComparison = java.lang.Long.compareUnsigned(firstHigh, secondHigh)
    return if (highComparison != 0) highComparison else java.lang.Long.compareUnsigned(firstLow, secondLow)
}

private fun sortIpv4(starts: IntArray, ends: IntArray, countries: IntArray, last: Int) {
    if (last < 1) return
    val order = Array(last + 1) { it }
    Arrays.sort(order, Comparator { first, second ->
        Integer.compareUnsigned(starts[first], starts[second])
    })
    val sortedStarts = IntArray(last + 1)
    val sortedEnds = IntArray(last + 1)
    val sortedCountries = IntArray(last + 1)
    order.forEachIndexed { index, source ->
        sortedStarts[index] = starts[source]
        sortedEnds[index] = ends[source]
        sortedCountries[index] = countries[source]
    }
    sortedStarts.copyInto(starts)
    sortedEnds.copyInto(ends)
    sortedCountries.copyInto(countries)
}

private fun sortIpv6(
    startHigh: LongArray,
    startLow: LongArray,
    endHigh: LongArray,
    endLow: LongArray,
    countries: IntArray,
    last: Int,
) {
    if (last < 1) return
    val order = Array(last + 1) { it }
    Arrays.sort(order, Comparator { first, second ->
        compareUnsigned(
            startHigh[first],
            startLow[first],
            startHigh[second],
            startLow[second],
        )
    })
    val sortedStartHigh = LongArray(last + 1)
    val sortedStartLow = LongArray(last + 1)
    val sortedEndHigh = LongArray(last + 1)
    val sortedEndLow = LongArray(last + 1)
    val sortedCountries = IntArray(last + 1)
    order.forEachIndexed { index, source ->
        sortedStartHigh[index] = startHigh[source]
        sortedStartLow[index] = startLow[source]
        sortedEndHigh[index] = endHigh[source]
        sortedEndLow[index] = endLow[source]
        sortedCountries[index] = countries[source]
    }
    sortedStartHigh.copyInto(startHigh)
    sortedStartLow.copyInto(startLow)
    sortedEndHigh.copyInto(endHigh)
    sortedEndLow.copyInto(endLow)
    sortedCountries.copyInto(countries)
}
