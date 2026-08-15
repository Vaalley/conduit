package eu.mctraveler.map

import eu.mctraveler.MinecraftTestBootstrap
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.MapPostProcessing
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class MapItemGuardTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() = MinecraftTestBootstrap.ensure()
    }

    @Test
    fun `empty stack or non map stack returns false`() {
        assertFalse(MapItemGuard.isTreasureOrExplorerMap(ItemStack.EMPTY))
        assertFalse(MapItemGuard.isTreasureOrExplorerMap(ItemStack(Items.STICK)))
        assertFalse(MapItemGuard.isTreasureOrExplorerMap(ItemStack(Items.FILLED_MAP)))
        assertFalse(MapItemGuard.isTreasureOrExplorerMap(ItemStack(Items.MAP)))
    }

    @Test
    fun `map stack with MAP_POST_PROCESSING LOCK returns true`() {
        val mapStack = ItemStack(Items.FILLED_MAP)
        mapStack.set(DataComponents.MAP_POST_PROCESSING, MapPostProcessing.LOCK)
        assertTrue(MapItemGuard.isTreasureOrExplorerMap(mapStack))
    }

    @Test
    fun `empty map stack with MAP_POST_PROCESSING LOCK returns true`() {
        val emptyMap = ItemStack(Items.MAP)
        emptyMap.set(DataComponents.MAP_POST_PROCESSING, MapPostProcessing.LOCK)
        assertTrue(MapItemGuard.isTreasureOrExplorerMap(emptyMap))
    }
}
