package eu.mctraveler.rtp

import eu.mctraveler.MinecraftTestBootstrap
import kotlin.math.hypot
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class RtpPickerTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() = MinecraftTestBootstrap.ensure()
    }

    private val settings = RtpConfig.Settings(radius = 1000, minDistance = 200, cooldownSeconds = 0)

    @Test
    fun `every candidate lies in the ring around the centre`() {
        val random = Random(42)
        repeat(10_000) {
            val (x, z) = RtpPicker.candidate(random, settings, centerX = 500, centerZ = -300)
            val distance = hypot((x - 500).toDouble(), (z + 300).toDouble())
            // Truncation to whole blocks may shave a block off either edge.
            assertTrue(distance >= settings.minDistance - 1, "$x,$z is inside the hole ($distance)")
            assertTrue(distance <= settings.radius + 1, "$x,$z is beyond the radius ($distance)")
        }
    }

    @Test
    fun `candidates favour the outer ring in proportion to its area`() {
        val random = Random(7)
        val far = (1..20_000).count {
            val (x, z) = RtpPicker.candidate(random, settings, 0, 0)
            hypot(x.toDouble(), z.toDouble()) > 700
        }
        // The band 700..1000 holds (1000² − 700²) / (1000² − 200²) ≈ 53% of the ring.
        assertTrue(far in 10_000..11_300, "$far of 20 000 landed past 700")
    }

    @Test
    fun `grass under two blocks of air is safe ground`() {
        assertTrue(RtpPicker.isSafeGround(Blocks.GRASS_BLOCK.defaultBlockState(), air(), air()))
        assertTrue(RtpPicker.isSafeGround(Blocks.STONE.defaultBlockState(), air(), air()))
    }

    @Test
    fun `water, lava, hostile blocks and a low ceiling are not`() {
        val air = air()
        assertFalse(RtpPicker.isSafeGround(Blocks.WATER.defaultBlockState(), air, air), "water")
        assertFalse(RtpPicker.isSafeGround(Blocks.LAVA.defaultBlockState(), air, air), "lava")
        assertFalse(RtpPicker.isSafeGround(Blocks.MAGMA_BLOCK.defaultBlockState(), air, air), "magma")
        assertFalse(RtpPicker.isSafeGround(Blocks.CACTUS.defaultBlockState(), air, air), "cactus")
        assertFalse(RtpPicker.isSafeGround(Blocks.POWDER_SNOW.defaultBlockState(), air, air), "powder snow")
        assertFalse(RtpPicker.isSafeGround(Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(), air), "wet feet")
        assertFalse(RtpPicker.isSafeGround(Blocks.STONE.defaultBlockState(), air, Blocks.STONE.defaultBlockState()), "no headroom")
    }

    private fun air() = Blocks.AIR.defaultBlockState()
}
