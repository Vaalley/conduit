package eu.mctraveler.crystal

import eu.mctraveler.persistence.JsonPlayerStore
import eu.mctraveler.persistence.PlayerStore
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Crystal energy (spec User Stories 24-25): 0-5 points shared by all a player's
 * crystals, one point back per 15 minutes of *play time*, the clock starting
 * when they first drop below full. Nucleus's `modifyEnergy` and its regen task
 * are the behaviour of record.
 *
 * Asserted through the real [JsonPlayerStore] over a temp directory — the store
 * is the seam energy lives behind, and the importer (ticket 05) writes the same
 * fields.
 */
class CrystalEnergyTest {
    @TempDir
    lateinit var dir: Path

    private val uuid: UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    private fun store(): PlayerStore = JsonPlayerStore(dir)

    @Test
    fun `a player never seen before is at full energy with no recharge pending`() {
        val store = store()
        assertEquals(5, CrystalEnergy.energyOf(store, uuid))
        assertNull(CrystalEnergy.nextRegenAt(store, uuid))
    }

    @Test
    fun `spending the first energy starts the recharge clock 15 play-time minutes out`() {
        val store = store()
        CrystalEnergy.modify(store, uuid, -1, playTimeTicks = 1000)
        assertEquals(4, CrystalEnergy.energyOf(store, uuid))
        assertEquals(1000 + 18000, CrystalEnergy.nextRegenAt(store, uuid))
    }

    @Test
    fun `spending more energy does not push the recharge clock back`() {
        val store = store()
        CrystalEnergy.modify(store, uuid, -1, playTimeTicks = 1000)
        CrystalEnergy.modify(store, uuid, -1, playTimeTicks = 5000)
        assertEquals(3, CrystalEnergy.energyOf(store, uuid))
        assertEquals(1000 + 18000, CrystalEnergy.nextRegenAt(store, uuid))
    }

    @Test
    fun `energy is clamped to 0 and 5`() {
        val store = store()
        CrystalEnergy.modify(store, uuid, -9, playTimeTicks = 0)
        assertEquals(0, CrystalEnergy.energyOf(store, uuid))
        CrystalEnergy.modify(store, uuid, +9, playTimeTicks = 0)
        assertEquals(5, CrystalEnergy.energyOf(store, uuid))
    }

    @Test
    fun `energy survives a fresh store over the same directory`() {
        CrystalEnergy.modify(store(), uuid, -2, playTimeTicks = 400)
        assertEquals(3, CrystalEnergy.energyOf(store(), uuid))
        assertEquals(400 + 18000, CrystalEnergy.nextRegenAt(store(), uuid))
    }

    @Test
    fun `a full player never regenerates`() {
        val store = store()
        assertFalse(CrystalEnergy.regen(store, uuid, playTimeTicks = 1_000_000))
        assertEquals(5, CrystalEnergy.energyOf(store, uuid))
    }

    @Test
    fun `regen waits for the threshold and then grants exactly one point`() {
        val store = store()
        CrystalEnergy.modify(store, uuid, -2, playTimeTicks = 0)
        assertFalse(CrystalEnergy.regen(store, uuid, playTimeTicks = 17999), "not due yet")
        assertEquals(3, CrystalEnergy.energyOf(store, uuid))
        assertTrue(CrystalEnergy.regen(store, uuid, playTimeTicks = 18000), "due at the threshold")
        assertEquals(4, CrystalEnergy.energyOf(store, uuid))
    }

    @Test
    fun `each further point costs another 15 play-time minutes`() {
        val store = store()
        CrystalEnergy.modify(store, uuid, -2, playTimeTicks = 0)
        CrystalEnergy.regen(store, uuid, playTimeTicks = 18000)
        assertEquals(4, CrystalEnergy.energyOf(store, uuid))
        assertEquals(36000, CrystalEnergy.nextRegenAt(store, uuid))
        assertFalse(CrystalEnergy.regen(store, uuid, playTimeTicks = 35999))
        assertTrue(CrystalEnergy.regen(store, uuid, playTimeTicks = 36000))
        assertEquals(5, CrystalEnergy.energyOf(store, uuid))
    }

    @Test
    fun `reaching full energy stops the clock`() {
        val store = store()
        CrystalEnergy.modify(store, uuid, -1, playTimeTicks = 0)
        assertTrue(CrystalEnergy.regen(store, uuid, playTimeTicks = 18000))
        assertEquals(5, CrystalEnergy.energyOf(store, uuid))
        assertNull(CrystalEnergy.nextRegenAt(store, uuid), "a full player has no recharge pending")
    }

    @Test
    fun `an empty player with no clock recharges at the next check`() {
        // Nucleus's loop treats a missing threshold as "due now" — the state an
        // imported Nucleus player can arrive in (energy saved, threshold not).
        val store = store()
        store.setCrystalEnergy(uuid, 0)
        assertTrue(CrystalEnergy.regen(store, uuid, playTimeTicks = 0))
        assertEquals(1, CrystalEnergy.energyOf(store, uuid))
        assertEquals(18000, CrystalEnergy.nextRegenAt(store, uuid))
    }
}
