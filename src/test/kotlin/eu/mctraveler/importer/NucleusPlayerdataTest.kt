package eu.mctraveler.importer

import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Reading Nucleus's crystal energy out of a Bukkit save (spec User Story 39).
 */
class NucleusPlayerdataTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private fun save(container: String? = "BukkitValues", fill: CompoundTag.() -> Unit = {}) =
        CompoundTag().apply {
            putString("Dimension", "minecraft:overworld")
            container?.let { put(it, CompoundTag().apply(fill)) }
        }

    @Test
    fun `both values come out of the entity's own container`() {
        val tag = save {
            putInt(NucleusPlayerdata.ENERGY_KEY, 2)
            putInt(NucleusPlayerdata.NEXT_REGEN_AT_KEY, 1_234_567)
        }

        assertEquals(
            NucleusPlayerdata.Energy(energy = 2, nextRegenAt = 1_234_567, container = "BukkitValues"),
            NucleusPlayerdata.energyOf(tag),
        )
    }

    /**
     * The other spelling CraftBukkit uses for a persistent data container. Which
     * one a player save carries cannot be settled without a real Bukkit save, so
     * both are read and the answer is reported rather than assumed.
     */
    @Test
    fun `the item-meta spelling of the container is read too`() {
        val tag = save(container = "PublicBukkitValues") { putInt(NucleusPlayerdata.ENERGY_KEY, 0) }

        assertEquals(
            NucleusPlayerdata.Energy(energy = 0, nextRegenAt = null, container = "PublicBukkitValues"),
            NucleusPlayerdata.energyOf(tag),
        )
    }

    @Test
    fun `a full player has energy and no threshold`() {
        val tag = save { putInt(NucleusPlayerdata.ENERGY_KEY, 3) }

        assertEquals(3, NucleusPlayerdata.energyOf(tag)?.energy)
        assertNull(NucleusPlayerdata.energyOf(tag)?.nextRegenAt)
    }

    @Test
    fun `a save with no container says nothing`() {
        assertNull(NucleusPlayerdata.energyOf(save(container = null)))
    }

    @Test
    fun `a container holding other plugins' keys says nothing`() {
        val tag = save { putString("mctravelernucleus:username", "someone") }

        assertNull(NucleusPlayerdata.energyOf(tag))
    }

    @Test
    fun `an empty container says nothing`() {
        assertNull(NucleusPlayerdata.energyOf(save()))
    }
}
