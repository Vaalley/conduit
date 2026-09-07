package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class SecondaryOffsetTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private fun createPlayerTag(dimension: String, x: Double, y: Double, z: Double, globalPosZ: Int): CompoundTag {
        return CompoundTag().apply {
            putString("Dimension", dimension)
            put("Pos", ListTag().apply {
                add(DoubleTag.valueOf(x))
                add(DoubleTag.valueOf(y))
                add(DoubleTag.valueOf(z))
            })
            put("LastDeathLocation", CompoundTag().apply {
                putString("dimension", dimension)
                putIntArray("pos", intArrayOf(x.toInt(), y.toInt(), globalPosZ))
            })
        }
    }

    @Test
    fun `merged correctly shifts Pos and GlobalPos for legacy and secondary overworld dimension strings`() {
        val offset = MergeOffset(0, 802816)
        val dimensionStrings = listOf("last", "minecraft:last", "mctraveler:secondary")

        for (dim in dimensionStrings) {
            val initialX = 100.0
            val initialY = 64.0
            val initialZ = 200.0
            val initialGlobalZ = 200

            val tag = createPlayerTag(dim, initialX, initialY, initialZ, initialGlobalZ)
            val merged = MergedPlayerdata.merged(tag, offset)

            // Primary overworld dimension id
            val expectedDimension = WorldLayout.PRIMARY.dimensionId(DimensionRole.OVERWORLD)
            assertEquals(expectedDimension, merged.getStringOr("Dimension", ""))

            val pos = merged.getListOrEmpty("Pos")
            assertEquals(3, pos.size)
            assertEquals(initialX, pos.getDoubleOr(0, 0.0), 0.0001)
            assertEquals(initialY, pos.getDoubleOr(1, 0.0), 0.0001)
            assertEquals(initialZ + 802816.0, pos.getDoubleOr(2, 0.0), 0.0001)

            val deathLoc = merged.getCompound("LastDeathLocation").orElse(null)
            assert(deathLoc != null)
            assertEquals(expectedDimension, deathLoc!!.getStringOr("dimension", ""))
            val globalPos = deathLoc.getIntArray("pos").orElse(null)
            assert(globalPos != null)
            assertEquals(initialX.toInt(), globalPos!![0])
            assertEquals(initialY.toInt(), globalPos[1])
            assertEquals(initialGlobalZ + 802816, globalPos[2])
        }
    }
}
