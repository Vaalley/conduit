package eu.mctraveler.passport

data class Stamp(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val unlocked: (StampContext) -> Boolean,
)

data class StampContext(
    val passport: Passport,
    val embassies: Int,
    val overworldBiomeTotal: Int,
    val now: Long,
)

object Stamps {
    val ALL: List<Stamp> = listOf(
        Stamp("first_steps", "First Steps", "Travelled 1 km", "👣") { it.passport.distance.total >= 1_000.0 },
        Stamp("wanderer", "Wanderer", "Travelled 25 km", "🥾") { it.passport.distance.total >= 25_000.0 },
        Stamp("nomad", "Nomad", "Travelled 100 km", "🧭") { it.passport.distance.total >= 100_000.0 },
        Stamp("biome_10", "Explorer", "Visited 10 biomes", "🌿") { it.passport.biomes.size >= 10 },
        Stamp("biome_30", "Naturalist", "Visited 30 biomes", "🌲") { it.passport.biomes.size >= 30 },
        // The passport biome map is the authoritative visited set; the total is only
        // used to avoid unlocking this stamp when the server could not provide it.
        Stamp("biome_all", "Cartographer", "Visited every Overworld biome", "🗺️") {
            it.overworldBiomeTotal > 0 && it.passport.biomes.size >= it.overworldBiomeTotal
        },
        Stamp("three_worlds", "Three Worlds", "Visited the Overworld, Nether and End", "🌌") {
            setOf(
                "minecraft:overworld",
                "minecraft:the_nether",
                "minecraft:the_end",
            ).all(it.passport.dimensions::contains)
        },
        Stamp("diplomat", "Diplomat", "Visited 5 embassies", "🤝") { it.embassies >= 5 },
        Stamp("ambassador", "Ambassador", "Visited 25 embassies", "🏛️") { it.embassies >= 25 },
        Stamp("crystal_5", "Frequent Flyer", "5 crystal trips", "💎") { it.passport.crystalTrips >= 5 },
        Stamp("crystal_50", "Jetsetter", "50 crystal trips", "✈️") { it.passport.crystalTrips >= 50 },
        Stamp("postcard_1", "Pen Pal", "Sent a postcard", "📮") { it.passport.postcards >= 1 },
        Stamp("postcard_10", "Postmaster", "Sent 10 postcards", "📬") { it.passport.postcards >= 10 },
        Stamp("veteran", "Veteran", "One year on MCTraveler", "🎖️") {
            it.now - it.passport.firstJoin >= 365L * 24 * 60 * 60 * 1000
        },
    )

    fun byId(id: String): Stamp? = ALL.firstOrNull { it.id == id }

    /** Adds newly satisfied stamps to passport.stamps (at = now) and returns them. */
    fun evaluate(context: StampContext): List<Stamp> =
        ALL.filter { it.unlocked(context) && context.passport.stamps.putIfAbsent(it.id, context.now) == null }
}
