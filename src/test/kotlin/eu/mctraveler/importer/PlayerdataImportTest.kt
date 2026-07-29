package eu.mctraveler.importer

import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The playerdata merge: on the Portal a player had one save per backend, and
 * the merged server keeps one — their last World's — while the other World's
 * position and bed become that World's Per-World Bucket.
 */
class PlayerdataImportTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    /** A backend playerdata tag as a vanilla 1.21.10 server wrote it. */
    private fun playerdata(
        dimension: String = "minecraft:overworld",
        pos: Triple<Double, Double, Double> = Triple(1.5, 64.0, -2.5),
        rotation: Pair<Float, Float> = 90f to -10f,
    ) = CompoundTag().apply {
        putString("Dimension", dimension)
        put("Pos", ListTag().apply {
            add(DoubleTag.valueOf(pos.first))
            add(DoubleTag.valueOf(pos.second))
            add(DoubleTag.valueOf(pos.third))
        })
        put("Rotation", ListTag().apply {
            add(FloatTag.valueOf(rotation.first))
            add(FloatTag.valueOf(rotation.second))
        })
        putInt("XpLevel", 30)
    }

    /** The respawn compound as 1.21.5–1.21.10 wrote it (yaw is still `angle`). */
    private fun respawn(dimension: String, x: Int, y: Int, z: Int, forced: Boolean = false) =
        CompoundTag().apply {
            putIntArray("pos", intArrayOf(x, y, z))
            putFloat("angle", 45f)
            putString("dimension", dimension)
            putBoolean("forced", forced)
        }

    @Test
    fun `a player last seen in Secondary wakes up in Secondary's own nether`() {
        val tag = playerdata(dimension = "minecraft:the_nether")

        val live = PlayerdataImport.live(tag, WorldLayout.SECONDARY)

        assertEquals("mctraveler:secondary_nether", live.getStringOr("Dimension", ""))
    }

    @Test
    fun `a player last seen in Primary keeps the vanilla trio`() {
        val tag = playerdata(dimension = "minecraft:the_end")

        val live = PlayerdataImport.live(tag, WorldLayout.PRIMARY)

        assertEquals("minecraft:the_end", live.getStringOr("Dimension", ""))
    }

    @Test
    fun `everything else in the live save rides along untouched`() {
        val live = PlayerdataImport.live(playerdata(), WorldLayout.SECONDARY)

        assertEquals(30, live.getIntOr("XpLevel", 0))
        assertEquals(1.5, live.getListOrEmpty("Pos").getDoubleOr(0, 0.0))
    }

    @Test
    fun `a bed standing in Secondary is re-pointed at Secondary`() {
        val tag = playerdata().apply { put("respawn", respawn("minecraft:overworld", 10, 70, 20)) }

        val live = PlayerdataImport.live(tag, WorldLayout.SECONDARY)

        assertEquals(
            "mctraveler:secondary",
            live.getCompoundOrEmpty("respawn").getStringOr("dimension", ""),
        )
    }

    @Test
    fun `a pre-1_21_5 spawn point is re-pointed too`() {
        val tag = playerdata().apply { putString("SpawnDimension", "minecraft:the_nether") }

        val live = PlayerdataImport.live(tag, WorldLayout.SECONDARY)

        assertEquals("mctraveler:secondary_nether", live.getStringOr("SpawnDimension", ""))
    }

    @Test
    fun `the other World's save seeds that World's Position Memory`() {
        val tag = playerdata(
            dimension = "minecraft:the_end",
            pos = Triple(100.5, 65.0, -200.25),
            rotation = 12.5f to -3.5f,
        )

        val bucket = PlayerdataImport.bucket(tag)

        assertEquals("end", bucket.dimension)
        assertEquals(100.5, bucket.x)
        assertEquals(65.0, bucket.y)
        assertEquals(-200.25, bucket.z)
        assertEquals(12.5f, bucket.yaw)
        assertEquals(-3.5f, bucket.pitch)
    }

    @Test
    fun `a bed in the other World becomes that World's respawn point`() {
        val tag = playerdata().apply {
            put("respawn", respawn("minecraft:the_nether", 3, 70, -8, forced = true))
        }

        val point = checkNotNull(PlayerdataImport.bucket(tag).respawn)

        assertEquals("nether", point.dimension)
        assertEquals(3, point.x)
        assertEquals(70, point.y)
        assertEquals(-8, point.z)
        assertEquals(45f, point.yaw)
        assertEquals(true, point.forced)
    }

    @Test
    fun `a pre-1_21_5 spawn point becomes a respawn point all the same`() {
        val tag = playerdata().apply {
            putInt("SpawnX", 7)
            putInt("SpawnY", 71)
            putInt("SpawnZ", -13)
            putFloat("SpawnAngle", 180f)
            putString("SpawnDimension", "minecraft:overworld")
            putBoolean("SpawnForced", true)
        }

        val point = checkNotNull(PlayerdataImport.bucket(tag).respawn)

        assertEquals("overworld", point.dimension)
        assertEquals(7, point.x)
        assertEquals(71, point.y)
        assertEquals(-13, point.z)
        assertEquals(180f, point.yaw)
        assertEquals(true, point.forced)
    }

    @Test
    fun `a player with no bed has no respawn point in that World`() {
        assertNull(PlayerdataImport.bucket(playerdata()).respawn)
    }

    @Test
    fun `playerdata from a dimension no World owns is refused rather than guessed at`() {
        val tag = playerdata(dimension = "someothermod:limbo")

        val error = assertThrows(IllegalArgumentException::class.java) {
            PlayerdataImport.bucket(tag)
        }

        assertEquals(
            "playerdata is in \"someothermod:limbo\", which is not part of a backend's trio",
            error.message,
        )
    }
}
