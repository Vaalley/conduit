package eu.mctraveler.crystal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpawnPlacementTest {

    @Test
    fun theSpotIsTheMiddleOfTheBlock() {
        assertEquals(16.5, SpawnPlacement.centre(16.9))
        assertEquals(16.5, SpawnPlacement.centre(16.0))
        assertEquals(-15.5, SpawnPlacement.centre(-15.2))
        assertEquals(-0.5, SpawnPlacement.centre(-0.01))
    }

    @Test
    fun yawSnapsToTheNearestCardinalDirection() {
        assertEquals(0f, SpawnPlacement.snapYaw(10f))
        assertEquals(0f, SpawnPlacement.snapYaw(-44f))
        assertEquals(90f, SpawnPlacement.snapYaw(46f))
        assertEquals(180f, SpawnPlacement.snapYaw(170f))
        assertEquals(180f, SpawnPlacement.snapYaw(-170f))
        assertEquals(270f, SpawnPlacement.snapYaw(-80f))
        assertEquals(270f, SpawnPlacement.snapYaw(260f))
    }

    @Test
    fun theFacingIsNamedByCompass() {
        assertEquals("south", SpawnPlacement.facingName(5f))
        assertEquals("west", SpawnPlacement.facingName(85f))
        assertEquals("north", SpawnPlacement.facingName(200f))
        assertEquals("east", SpawnPlacement.facingName(-90f))
    }
}
