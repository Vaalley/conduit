package eu.mctraveler.persistence

/**
 * The Per-World Bucket (ADR 0001): the player state a World keeps separately,
 * as persisted per (player, World) — Position Memory (where the player last
 * stood, and in which dimension of the World's trio) and the [respawn] point
 * their bed or anchor set there.
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
    /** Where death sends the player in this World, or null — no bed or anchor set here yet. */
    val respawn: RespawnPoint? = null,
)

/**
 * A respawn point as one World remembers it (spec story 22): the block a bed or
 * respawn anchor stands on, plus the facing to wake up with. Per-World, because
 * a bed counts only for deaths in the World it stands in.
 *
 * [dimension] is the trio-relative role, as in [PerWorldBucket]. [forced] is
 * vanilla's "this point needs no block to back it" flag — what `/spawnpoint`
 * sets, as opposed to sleeping in a bed.
 */
data class RespawnPoint(
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val yaw: Float,
    val pitch: Float,
    val forced: Boolean,
)
