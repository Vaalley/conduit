package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The Per-World Bucket, as the migration tools read and write it.
 *
 * These cases were `JsonPlayerStoreTest`'s until the Worlds were retired and the
 * live persistence model stopped owning the field. They moved rather than being
 * deleted because nothing about the on-disk format changed: `migrate` still
 * writes buckets, `mergeWorlds` still reads Secondary's and moves it, and the
 * claim path still banks a returning player's other save into one. The one thing
 * that did change is who is responsible for the bytes, which is what
 * [PerWorldBuckets] is.
 */
class PerWorldBucketsTest {
    @TempDir
    lateinit var dir: Path

    private val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    private fun record(): Path = dir.resolve("$uuid.json")

    @Test
    fun `a player who never visited a World has no bucket there`() {
        assertNull(PerWorldBuckets.of(record(), "secondary"))
    }

    @Test
    fun `Per-World Buckets round-trip independently per World`() {
        val primary = PerWorldBucket("nether", 100.5, 64.0, -20.5, 90.0f, -10.5f)
        val secondary = PerWorldBucket("overworld", -3.25, 70.0, 8.0, 0.0f, 0.0f)
        PerWorldBuckets.into(record(), "primary", primary)
        PerWorldBuckets.into(record(), "secondary", secondary)
        assertEquals(primary, PerWorldBuckets.of(record(), "primary"))
        assertEquals(secondary, PerWorldBuckets.of(record(), "secondary"))
    }

    @Test
    fun `a bucket carries no respawn point until one is stored`() {
        PerWorldBuckets.into(record(), "primary", PerWorldBucket("overworld", 1.5, 2.0, 3.5, 0.0f, 0.0f))
        assertNull(PerWorldBuckets.of(record(), "primary")?.respawn)
    }

    @Test
    fun `respawn points round-trip independently per World`() {
        val primaryBed = RespawnPoint("overworld", 10, 64, -20, 90.0f, 0.0f, forced = false)
        val secondaryAnchor = RespawnPoint("nether", -3, 40, 8, 180.0f, -12.5f, forced = true)
        PerWorldBuckets.into(record(), "primary", bucketWith(primaryBed))
        PerWorldBuckets.into(record(), "secondary", bucketWith(secondaryAnchor))
        assertEquals(primaryBed, PerWorldBuckets.of(record(), "primary")?.respawn)
        assertEquals(secondaryAnchor, PerWorldBuckets.of(record(), "secondary")?.respawn)
    }

    @Test
    fun `a stored respawn point can be cleared again`() {
        PerWorldBuckets.into(record(), "primary", bucketWith(RespawnPoint("end", 1, 2, 3, 0.0f, 0.0f, false)))
        PerWorldBuckets.into(record(), "primary", bucketWith(null))
        assertNull(PerWorldBuckets.of(record(), "primary")?.respawn)
    }

    private fun bucketWith(respawn: RespawnPoint?) =
        PerWorldBucket("overworld", 0.5, 70.0, 0.5, 0.0f, 0.0f, respawn)

    @Test
    fun `rewriting one World's bucket leaves the other World's and legacy fields untouched`() {
        // A record as the live deployment holds one: legacy economy data, a
        // bucket field carrying keys this version doesn't know, and
        // predecessor-era number formatting throughout. The merge sweep rewrites
        // exactly one slice of exactly one of these and must disturb nothing
        // else, which is the guarantee the whole staging discipline rests on.
        val file = record()
        Files.writeString(
            file,
            """{"balance":1234.50,"worlds":{"secondary":{"dimension":"end",""" +
                """"x":1.0,"y":2.0,"z":3.0,"yaw":0.0,"pitch":0.0,"futureRespawn":{"x":1}}},""" +
                """"lastServer":"secondary"}""",
        )
        PerWorldBuckets.into(file, "primary", PerWorldBucket("overworld", 0.5, 80.0, 0.5, 180.0f, 0.0f))
        val written = Files.readString(file)
        assert(
            written.contains(
                """"secondary":{"dimension":"end","x":1.0,"y":2.0,"z":3.0,""" +
                    """"yaw":0.0,"pitch":0.0,"futureRespawn":{"x":1}}""",
            ),
        ) { "secondary bucket was not preserved verbatim: $written" }
        assert(written.contains(""""balance":1234.50""")) { "legacy field disturbed: $written" }
        assert(written.contains(""""lastServer":"secondary"""")) { "legacy field disturbed: $written" }
        assertEquals(
            PerWorldBucket("overworld", 0.5, 80.0, 0.5, 180.0f, 0.0f),
            PerWorldBuckets.of(file, "primary"),
        )
    }

    @Test
    fun `writing a bucket keeps the field in the place the record already had it`() {
        // The sweep rewrites Secondary's bucket in place, so a rehearsal's diff
        // against the real run says only what the merge did — a field that
        // migrated to the end of the object would make every swept record look
        // changed in a second way.
        val file = record()
        Files.writeString(
            file,
            """{"worlds":{"primary":{"dimension":"overworld","x":1.0,"y":2.0,"z":3.0,""" +
                """"yaw":0.0,"pitch":0.0}},"balance":7.50}""",
        )
        PerWorldBuckets.into(file, "primary", PerWorldBucket("nether", 4.0, 5.0, 6.0, 0.0f, 0.0f))
        assertEquals(
            """{"worlds":{"primary":{"dimension":"nether","x":4.0,"y":5.0,"z":6.0,""" +
                """"yaw":0.0,"pitch":0.0}},"balance":7.50}""",
            Files.readString(file),
        )
    }
}
