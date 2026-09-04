package eu.mctraveler.passport

/**
 * Persistent travel record for one player.
 *
 * Region ids are tree paths rather than titles, so a renamed region keeps its
 * visit while a deleted region becomes an orphan that readers can ignore.
 */
data class Passport(
    var firstJoin: Long,
    val biomes: LinkedHashMap<String, Long> = LinkedHashMap(),
    val dimensions: LinkedHashMap<String, Long> = LinkedHashMap(),
    val regions: LinkedHashMap<String, Long> = LinkedHashMap(),
    val distance: Distance = Distance(),
    var deaths: Int = 0,
)

data class Distance(
    var walk: Double = 0.0,
    var ride: Double = 0.0,
    var fly: Double = 0.0,
    var swim: Double = 0.0,
) {
    val total: Double get() = walk + ride + fly + swim

    fun add(bucket: Bucket, amount: Double) {
        when (bucket) {
            Bucket.WALK -> walk += amount
            Bucket.RIDE -> ride += amount
            Bucket.FLY -> fly += amount
            Bucket.SWIM -> swim += amount
        }
    }

    enum class Bucket {
        WALK,
        RIDE,
        FLY,
        SWIM,
    }

    companion object {
        fun bucketFor(flying: Boolean, riding: Boolean, swimming: Boolean): Bucket =
            when {
                flying -> Bucket.FLY
                riding -> Bucket.RIDE
                swimming -> Bucket.SWIM
                else -> Bucket.WALK
            }
    }
}

object PassportFormatting {
    fun formatDistance(blocks: Double): String =
        if (blocks < 1000.0) "${blocks.roundToNearestInt()} m"
        else "%.1f km".format(java.util.Locale.ROOT, blocks / 1000.0)

    private fun Double.roundToNearestInt(): Long = kotlin.math.round(this).toLong()
}
