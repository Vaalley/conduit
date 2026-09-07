package eu.mctraveler.crystal

import eu.mctraveler.MinecraftTestBootstrap
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
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
    fun `the charge capacity follows the tier mapping`() {
        for ((tier, charges) in mapOf(1 to 1, 2 to 3, 3 to 5)) {
            assertEquals(charges, CrystalItem.chargesOf(tier))
            assertEquals(charges, CrystalItem.of(tier).get(DataComponents.MAX_DAMAGE), "tier $tier max damage")
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
                "Charge capacity 3",
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

    @Test
    fun `charge capacity clamps unknown tiers`() {
        assertEquals(1, CrystalItem.chargesOf(0))
        assertEquals(1, CrystalItem.chargesOf(-1))
        assertEquals(5, CrystalItem.chargesOf(4))
    }

    // ---- Nucleus-era crystals (spec deviation 18) ----
    //
    // Bukkit's PersistentDataContainer writes into a `PublicBukkitValues`
    // compound inside custom_data, keyed by namespaced string, with booleans
    // stored as bytes. Crystals in migrated inventories, chests, ender chests
    // and shulkers still wear that layout, and no importer sweep can reach
    // them all — so identification has to know both.

    @Test
    fun `a Nucleus-era crystal is still a crystal`() {
        for (tier in 1..3) {
            val legacy = legacyCrystal(tier)
            assertTrue(CrystalItem.isCrystal(legacy), "a legacy tier-$tier crystal should be recognised")
            assertEquals(tier, CrystalItem.tierOf(legacy), "a legacy tier-$tier crystal's tier")
        }
    }

    @Test
    fun `a Nucleus-era crystal with no recorded tier reads as tier 3`() {
        // Nucleus's own default: `get(...) ?: 3`.
        assertEquals(3, CrystalItem.tierOf(legacyCrystal(tier = null)))
    }

    @Test
    fun `a Nucleus-era marker stored as a byte counts as true`() {
        // PersistentDataType.BOOLEAN is a byte on disk, so the tag may decode
        // as either depending on which writer last touched it.
        val legacy = legacyCrystal(tier = 2, markerAsByte = true)
        assertTrue(CrystalItem.isCrystal(legacy), "a byte-valued legacy marker should be recognised")
        assertEquals(2, CrystalItem.tierOf(legacy))
    }

    @Test
    fun `a Bukkit item carrying other plugin data is not a crystal`() {
        val stack = ItemStack(Items.ECHO_SHARD)
        CustomData.set(
            DataComponents.CUSTOM_DATA,
            stack,
            CompoundTag().apply {
                put(
                    "PublicBukkitValues",
                    CompoundTag().apply { putString("someplugin:owner", "Nobody") },
                )
            },
        )
        assertFalse(CrystalItem.isCrystal(stack))
    }

    @Test
    fun `a Nucleus-era marker set to false is not a crystal`() {
        assertFalse(CrystalItem.isCrystal(legacyCrystal(tier = 1, marker = false)))
    }

    /** An Echo Shard wearing Bukkit's PersistentDataContainer layout. */
    private fun legacyCrystal(
        tier: Int?,
        marker: Boolean = true,
        markerAsByte: Boolean = false,
    ): ItemStack {
        val stack = ItemStack(Items.ECHO_SHARD)
        val bukkit = CompoundTag().apply {
            if (markerAsByte) {
                putByte("mctravelernucleus:is-teleportation-crystal", if (marker) 1 else 0)
            } else {
                putBoolean("mctravelernucleus:is-teleportation-crystal", marker)
            }
            if (tier != null) putInt("mctravelernucleus:teleportation-crystal-tier", tier)
        }
        CustomData.set(
            DataComponents.CUSTOM_DATA,
            stack,
            CompoundTag().apply { put("PublicBukkitValues", bukkit) },
        )
        return stack
    }
}
