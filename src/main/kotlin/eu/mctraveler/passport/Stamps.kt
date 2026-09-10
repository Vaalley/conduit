package eu.mctraveler.passport

import net.minecraft.resources.Identifier
import net.minecraft.stats.Stats

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
    val regions: Int = 0,
    val stat: (Identifier) -> Int = { 0 },
)

object Stamps {
    val ALL: List<Stamp> = listOf(
        Stamp("first_steps", "First Steps", "Travelled 1 km", "👣") { it.passport.distance.total >= 1_000.0 },
        Stamp("wanderer", "Wanderer", "Travelled 25 km", "🥾") { it.passport.distance.total >= 25_000.0 },
        Stamp("nomad", "Nomad", "Travelled 100 km", "🧭") { it.passport.distance.total >= 100_000.0 },
        Stamp("globetrotter", "Globetrotter", "Travelled 1,000 km", "🌍") { it.passport.distance.total >= 1_000_000.0 },
        Stamp("marathon", "Marathon", "Walked a marathon (42.195 km)", "🏃") { it.passport.distance.walk >= 42_195.0 },
        Stamp("pony_express", "Pony Express", "Ridden 25 km", "🐎") { it.passport.distance.ride >= 25_000.0 },
        Stamp("channel_swimmer", "Channel Swimmer", "Swum 10 km", "🏊") { it.passport.distance.swim >= 10_000.0 },
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
        Stamp("sightseer", "Sightseer", "Visited 25 regions", "🏘️") { it.regions >= 25 },
        Stamp("trespasser", "Trespasser", "Visited 100 regions", "🕵️") { it.regions >= 100 },
        Stamp("diplomat", "Diplomat", "Visited 5 embassies", "🤝") { it.embassies >= 5 },
        Stamp("ambassador", "Ambassador", "Visited 25 embassies", "🏛️") { it.embassies >= 25 },
        Stamp("crystal_5", "Frequent Flyer", "5 crystal trips", "💎") { it.passport.crystalTrips >= 5 },
        Stamp("crystal_50", "Jetsetter", "50 crystal trips", "✈️") { it.passport.crystalTrips >= 50 },
        Stamp("wormhole", "Wormhole", "Used /rtp", "🎰") { it.passport.rtpUses >= 1 },
        Stamp("roulette_regular", "Roulette Regular", "Used /rtp 25 times", "🎲") { it.passport.rtpUses >= 25 },
        Stamp("postcard_1", "Pen Pal", "Sent a postcard", "📮") { it.passport.postcards >= 1 },
        Stamp("postcard_10", "Postmaster", "Sent 10 postcards", "📬") { it.passport.postcards >= 10 },
        Stamp("first_blood", "First Blood", "Died once", "🩸") { it.passport.deaths >= 1 },
        Stamp("leg_day", "Leg Day", "Jumped 10,000 times", "🐇") { it.stat(Stats.JUMP) >= 10_000 },
        Stamp("monster_hunter", "Monster Hunter", "Killed 500 mobs", "⚔️") { it.stat(Stats.MOB_KILLS) >= 500 },
        Stamp("sneaky", "Sneaky", "Sneaked for an hour", "🥷") { it.stat(Stats.CROUCH_TIME) >= 72_000 },
        Stamp("sleepyhead", "Sleepyhead", "Slept in a bed 100 times", "😴") { it.stat(Stats.SLEEP_IN_BED) >= 100 },
        Stamp("insomniac", "Insomniac", "Stayed awake for 5 in-game days", "🌙") { it.stat(Stats.TIME_SINCE_REST) >= 120_000 },
        Stamp("cake", "Have Your Cake", "Ate 50 cake slices", "🍰") { it.stat(Stats.EAT_CAKE_SLICE) >= 50 },
        Stamp("big_spender", "Big Spender", "Traded with villagers 100 times", "💰") { it.stat(Stats.TRADED_WITH_VILLAGER) >= 100 },
        Stamp("gone_fishing", "Gone Fishing", "Caught 25 fish", "🎣") { it.stat(Stats.FISH_CAUGHT) >= 25 },
        Stamp("veteran", "Veteran", "One year on MCTraveler", "🎖️") {
            it.now - it.passport.firstJoin >= 365L * 24 * 60 * 60 * 1000
        },
        // Granted at the moment they happen; evaluate never unlocks these.
        Stamp("ashes_to_ashes", "Ashes to Ashes", "Died in the Nether", "🔥") { false },
        Stamp("into_the_void", "Into the Void", "Fell into the End void", "🕳️") { false },
        Stamp("terminal_velocity", "Terminal Velocity", "Survived a 100 m fall", "🪂") { false },
        Stamp("storm_chaser", "Storm Chaser", "Stood in a thunderstorm", "⛈️") { false },
    )

    fun byId(id: String): Stamp? = ALL.firstOrNull { it.id == id }

    /** Adds newly satisfied stamps to passport.stamps (at = now) and returns them. */
    fun evaluate(context: StampContext): List<Stamp> =
        ALL.filter { it.unlocked(context) && context.passport.stamps.putIfAbsent(it.id, context.now) == null }

    /** Grants an event-driven stamp directly; returns it only when newly earned. */
    fun grant(passport: Passport, id: String, at: Long): Stamp? {
        val stamp = byId(id) ?: return null
        return if (passport.stamps.putIfAbsent(id, at) == null) stamp else null
    }
}
