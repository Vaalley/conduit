package eu.mctraveler

import net.minecraft.SharedConstants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Proves the fabric-loader-junit harness itself: Minecraft classes are on the test
 * classpath with mixins applied, and vanilla registries bootstrap without booting a
 * server — the foundation the unit tier (region geometry, text DSL, stores) builds on.
 */
class LoaderJUnitHarnessTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun `vanilla registries are available to unit tests`() {
        assertEquals("minecraft:stone", BuiltInRegistries.BLOCK.getKey(Blocks.STONE).toString())
    }
}
