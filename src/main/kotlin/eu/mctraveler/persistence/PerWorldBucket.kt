package eu.mctraveler.persistence

/**
 * The Per-World Bucket (ADR 0001): the player state a World keeps separately,
 * as persisted per (player, World). Version 1 is Position Memory — where the
 * player last stood, and in which dimension of the World's trio; ticket 05
 * adds the respawn point.
 *
 * [dimension] is the trio-relative role — `"overworld"`, `"nether"` or
 * `"end"` — not a full dimension id: which actual dimension that means is the
 * Worlds service's call, so buckets survive dimension-id changes and one
 * schema serves every World.
 */
data class PerWorldBucket(
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)
