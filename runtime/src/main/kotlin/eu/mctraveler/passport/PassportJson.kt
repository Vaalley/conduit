package eu.mctraveler.passport

import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionService
import java.util.UUID
import kotlin.math.roundToLong

object PassportJson {
    data class Summary(
        val name: String,
        val firstJoin: Long,
        val biomes: List<String>,
        val biomeCount: Int,
        val dimensions: List<String>,
        val regions: List<RegionSummary>,
        val distance: DistanceSummary,
        val deaths: Int,
        val rank: Rank,
    )

    data class RegionSummary(
        val id: String,
        val title: String,
        val embassy: Boolean,
        val owner: String?,
        val at: Long,
    )

    data class DistanceSummary(
        val walk: Long,
        val ride: Long,
        val fly: Long,
        val swim: Long,
        val total: Long,
    )

    data class Rank(
        val distance: Int,
        val biomes: Int,
        val embassies: Int,
    )

    /**
     * Builds the HTTP representation from the live region tree. Missing ids are
     * intentionally ignored: deleting a region must not erase its old visit.
     */
    fun summary(
        uuid: UUID,
        name: String,
        passport: Passport,
        regions: RegionService,
        nameFor: (UUID) -> String?,
        rank: Rank = Rank(0, 0, 0),
    ): Summary {
        val visited = passport.regions.mapNotNull { (id, at) ->
            val region = regions.byStableId(id) ?: return@mapNotNull null
            RegionSummary(
                id = id,
                title = region.title,
                embassy = Region.EMBASSY_FLAG in region.flags,
                owner = if (Region.EMBASSY_FLAG in region.flags) {
                    region.members.firstOrNull()?.let(nameFor)
                } else {
                    null
                },
                at = at,
            )
        }
        return Summary(
            name = name,
            firstJoin = passport.firstJoin,
            biomes = passport.biomes.keys.toList(),
            biomeCount = passport.biomes.size,
            dimensions = passport.dimensions.keys.toList(),
            regions = visited,
            distance = DistanceSummary(
                walk = passport.distance.walk.roundToLong(),
                ride = passport.distance.ride.roundToLong(),
                fly = passport.distance.fly.roundToLong(),
                swim = passport.distance.swim.roundToLong(),
                total = passport.distance.total.roundToLong(),
            ),
            deaths = passport.deaths,
            rank = rank,
        )
    }

    fun embassyCount(passport: Passport, regions: RegionService): Int =
        passport.regions.keys.count { id ->
            regions.byStableId(id)?.let { Region.EMBASSY_FLAG in it.flags } == true
        }
}
