package eu.mctraveler.crystal

import kotlin.math.floor
import kotlin.math.roundToInt

/** The pure geometry of `/setspawn`: where in a block a spawn sits and which way it faces. */
object SpawnPlacement {

    /** The middle of the block containing [coordinate]. */
    fun centre(coordinate: Double): Double = floor(coordinate) + 0.5

    /** [yaw] snapped to the nearest cardinal facing: 0 south, 90 west, 180 north, 270 east. */
    fun snapYaw(yaw: Float): Float {
        val quarter = (yaw / 90f).roundToInt()
        return ((((quarter * 90) % 360) + 360) % 360).toFloat()
    }

    fun facingName(yaw: Float): String = when (snapYaw(yaw).toInt()) {
        0 -> "south"
        90 -> "west"
        180 -> "north"
        else -> "east"
    }
}
