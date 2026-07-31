package eu.mctraveler.crystal

import eu.mctraveler.MinecraftTestBootstrap
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The Teleportation Crystal item (spec User Story 22): a re-skinned Echo Shard
 * identified by custom data, exactly as the Notepad identifies its stand-in book.
 * Expected texts are Nucleus's, from `teleportation-crystal.kt`.
 */
class CrystalItemTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() = MinecraftTestBootstrap.ensure()
    }

    @Test
    fun `a crystal is a single echo shard`() {
        val crystal = CrystalItem.of(1)
        assertTrue(crystal.`is`(Items.ECHO_SHARD), "expected an echo shard, found $crystal")
        assertEquals(1, crystal.count)
        assertEquals(1, crystal.get(DataComponents.MAX_STACK_SIZE))
    }

    @Test
    fun `every tier is identified as a crystal and remembers its tier`() {
        for (tier in 1..3) {
            val crystal = CrystalItem.of(tier)
            assertTrue(CrystalItem.isCrystal(crystal), "tier $tier should be identified as a crystal")
            assertEquals(tier, CrystalItem.tierOf(crystal), "tier $tier should remember its tier")
        }
    }

    @Test
    fun `a plain echo shard is not a crystal`() {
        assertFalse(CrystalItem.isCrystal(ItemStack(Items.ECHO_SHARD)))
    }

    @Test
    fun `an empty stack is not a crystal`() {
        assertFalse(CrystalItem.isCrystal(ItemStack.EMPTY))
    }

    @Test
    fun `the charge capacity is the tier`() {
        for (tier in 1..3) {
            assertEquals(tier, CrystalItem.of(tier).get(DataComponents.MAX_DAMAGE), "tier $tier max damage")
        }
    }

    @Test
    fun `a fresh crystal carries no damage - energy is per player, not per item`() {
        assertFalse(CrystalItem.of(3).has(DataComponents.DAMAGE))
    }

    @Test
    fun `the crystal is named and glinted`() {
        val crystal = CrystalItem.of(2)
        assertEquals("Teleportation Crystal", crystal.get(DataComponents.ITEM_NAME)?.string)
        assertEquals(true, crystal.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE))
    }

    @Test
    fun `the lore is Nucleus's four lines, the last naming the charge capacity`() {
        val lore = CrystalItem.of(2).get(DataComponents.LORE)?.lines().orEmpty()
        assertEquals(
            listOf(
                "The power of teleportation in your hands",
                "Recharges one use every 15 minutes",
                "",
                "Charge capacity 2",
            ),
            lore.map { it.string },
        )
        assertEquals("gold", lore.last().style.color?.serialize(), "the charge capacity line is gold")
    }

    @Test
    fun `tiers outside 1 to 3 are refused`() {
        for (tier in listOf(0, 4, -1)) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                CrystalItem.of(tier)
            }
        }
    }
}
