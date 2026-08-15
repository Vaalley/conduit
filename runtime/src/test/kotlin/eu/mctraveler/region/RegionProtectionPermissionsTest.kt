package eu.mctraveler.region

import eu.mctraveler.MinecraftTestBootstrap
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class RegionProtectionPermissionsTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() = MinecraftTestBootstrap.ensure()
    }

    @Test
    fun `food items are permitted by allowsItemUse`() {
        val apple = ItemStack(Items.APPLE)
        val cookedBeef = ItemStack(Items.COOKED_BEEF)
        val bread = ItemStack(Items.BREAD)

        assertTrue(RegionProtection.allowsItemUse(null, apple), "apple should be permitted")
        assertTrue(RegionProtection.allowsItemUse(null, cookedBeef), "cooked beef should be permitted")
        assertTrue(RegionProtection.allowsItemUse(null, bread), "bread should be permitted")
    }

    @Test
    fun `consumables are permitted by allowsItemUse`() {
        val potion = ItemStack(Items.POTION)
        val milkBucket = ItemStack(Items.MILK_BUCKET)
        val honeyBottle = ItemStack(Items.HONEY_BOTTLE)
        val goldenApple = ItemStack(Items.GOLDEN_APPLE)
        val enchantedGoldenApple = ItemStack(Items.ENCHANTED_GOLDEN_APPLE)

        assertTrue(RegionProtection.allowsItemUse(null, potion), "potion should be permitted")
        assertTrue(RegionProtection.allowsItemUse(null, milkBucket), "milk bucket should be permitted")
        assertTrue(RegionProtection.allowsItemUse(null, honeyBottle), "honey bottle should be permitted")
        assertTrue(RegionProtection.allowsItemUse(null, goldenApple), "golden apple should be permitted")
        assertTrue(RegionProtection.allowsItemUse(null, enchantedGoldenApple), "enchanted golden apple should be permitted")
    }

    @Test
    fun `fireworks are permitted by allowsItemUse`() {
        val firework = ItemStack(Items.FIREWORK_ROCKET)
        assertTrue(RegionProtection.allowsItemUse(null, firework), "firework rocket should be permitted")
    }

    @Test
    fun `enemy entities are permitted by allowsEntityAttack`() {
        assertTrue(RegionProtection.allowsEntityAttack(null, null), "null player or null entity should be permitted")
    }
}
