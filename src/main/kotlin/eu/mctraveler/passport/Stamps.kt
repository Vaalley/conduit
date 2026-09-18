package eu.mctraveler.passport

import eu.mctraveler.economy.Economy
import net.minecraft.resources.Identifier
import net.minecraft.stats.Stats

enum class StampTier(val bounty: Long) {
    COMMON(Economy.dollars(10)),
    UNCOMMON(Economy.dollars(20)),
    RARE(Economy.dollars(50)),
    EPIC(Economy.dollars(100)),
}

data class Stamp(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val tier: StampTier,
    /** Granted at the moment the feat happens; [Stamps.evaluate] never looks at it. */
    val eventOnly: Boolean = false,
    val unlocked: (StampContext) -> Boolean,
)

/**
 * What a stamp predicate may read. [regions] and [embassies] are suppliers
 * because computing either walks the player's visited-id set against the
 * region index — only the sightseer/trespasser/diplomat/ambassador predicates
 * read them, so passports that already have those stamps never pay for it.
 */
data class StampContext(
    val passport: Passport,
    val embassies: () -> Int,
    val overworldBiomeTotal: Int,
    val now: Long,
    val regions: () -> Int = { 0 },
    val stat: (Identifier) -> Int = { 0 },
)

object Stamps {
    val ALL: List<Stamp> = listOf(
        Stamp("first_steps", "First Steps", "Travelled 1 km", "👣", StampTier.COMMON) { it.passport.distance.total >= 1_000.0 },
        Stamp("wanderer", "Wanderer", "Travelled 25 km", "🥾", StampTier.UNCOMMON) { it.passport.distance.total >= 25_000.0 },
        Stamp("nomad", "Nomad", "Travelled 100 km", "🧭", StampTier.RARE) { it.passport.distance.total >= 100_000.0 },
        Stamp("globetrotter", "Globetrotter", "Travelled 1,000 km", "🌍", StampTier.EPIC) { it.passport.distance.total >= 1_000_000.0 },
        Stamp("marathon", "Marathon", "Walked a marathon (42.195 km)", "🏃", StampTier.RARE) { it.passport.distance.walk >= 42_195.0 },
        Stamp("pony_express", "Pony Express", "Ridden 25 km", "🐎", StampTier.UNCOMMON) { it.passport.distance.ride >= 25_000.0 },
        Stamp("channel_swimmer", "Channel Swimmer", "Swum 10 km", "🏊", StampTier.UNCOMMON) { it.passport.distance.swim >= 10_000.0 },
        Stamp("biome_10", "Explorer", "Visited 10 biomes", "🌿", StampTier.COMMON) { it.passport.biomes.size >= 10 },
        Stamp("biome_30", "Naturalist", "Visited 30 biomes", "🌲", StampTier.UNCOMMON) { it.passport.biomes.size >= 30 },
        // The passport biome map is the authoritative visited set; the total is only
        // used to avoid unlocking this stamp when the server could not provide it.
        Stamp("biome_all", "Cartographer", "Visited every Overworld biome", "🗺️", StampTier.EPIC) {
            it.overworldBiomeTotal > 0 && it.passport.biomes.size >= it.overworldBiomeTotal
        },
        Stamp("three_worlds", "Three Worlds", "Visited the Overworld, Nether and End", "🌌", StampTier.RARE) {
            setOf(
                "minecraft:overworld",
                "minecraft:the_nether",
                "minecraft:the_end",
            ).all(it.passport.dimensions::contains)
        },
        Stamp("sightseer", "Sightseer", "Visited 25 regions", "🏘️", StampTier.UNCOMMON) { it.regions() >= 25 },
        Stamp("trespasser", "Trespasser", "Visited 100 regions", "🕵️", StampTier.RARE) { it.regions() >= 100 },
        Stamp("diplomat", "Diplomat", "Visited 5 embassies", "🤝", StampTier.RARE) { it.embassies() >= 5 },
        Stamp("ambassador", "Ambassador", "Visited 25 embassies", "🏛️", StampTier.EPIC) { it.embassies() >= 25 },
        Stamp("crystal_5", "Frequent Flyer", "5 crystal trips", "💎", StampTier.UNCOMMON) { it.passport.crystalTrips >= 5 },
        Stamp("crystal_50", "Jetsetter", "50 crystal trips", "✈️", StampTier.RARE) { it.passport.crystalTrips >= 50 },
        Stamp("wormhole", "Wormhole", "Used /rtp", "🎰", StampTier.COMMON) { it.passport.rtpUses >= 1 },
        Stamp("roulette_regular", "Roulette Regular", "Used /rtp 25 times", "🎲", StampTier.RARE) { it.passport.rtpUses >= 25 },
        Stamp("postcard_1", "Pen Pal", "Sent a postcard", "📮", StampTier.COMMON) { it.passport.postcards >= 1 },
        Stamp("postcard_10", "Postmaster", "Sent 10 postcards", "📬", StampTier.UNCOMMON) { it.passport.postcards >= 10 },
        Stamp("digger", "Digger", "Mined 1,000 blocks", "⛏️", StampTier.COMMON) { it.passport.blocksMined >= 1_000 },
        Stamp("excavator", "Excavator", "Mined 10,000 blocks", "🧨", StampTier.UNCOMMON) { it.passport.blocksMined >= 10_000 },
        Stamp("mountain_mover", "Mountain Mover", "Mined 100,000 blocks", "🏔️", StampTier.EPIC) { it.passport.blocksMined >= 100_000 },
        Stamp("first_blood", "First Blood", "Died once", "🩸", StampTier.COMMON) { it.passport.deaths >= 1 },
        Stamp("leg_day", "Leg Day", "Jumped 10,000 times", "🐇", StampTier.RARE) { it.stat(Stats.JUMP) >= 10_000 },
        Stamp("monster_hunter", "Monster Hunter", "Killed 500 mobs", "⚔️", StampTier.RARE) { it.stat(Stats.MOB_KILLS) >= 500 },
        Stamp("sneaky", "Sneaky", "Sneaked for an hour", "🥷", StampTier.UNCOMMON) { it.stat(Stats.CROUCH_TIME) >= 72_000 },
        Stamp("sleepyhead", "Sleepyhead", "Slept in a bed 100 times", "😴", StampTier.RARE) { it.stat(Stats.SLEEP_IN_BED) >= 100 },
        Stamp("insomniac", "Insomniac", "Stayed awake for 5 in-game days", "🌙", StampTier.RARE) { it.stat(Stats.TIME_SINCE_REST) >= 120_000 },
        Stamp("cake", "Have Your Cake", "Ate 50 cake slices", "🍰", StampTier.UNCOMMON) { it.stat(Stats.EAT_CAKE_SLICE) >= 50 },
        Stamp("big_spender", "Big Spender", "Traded with villagers 100 times", "💰", StampTier.RARE) { it.stat(Stats.TRADED_WITH_VILLAGER) >= 100 },
        Stamp("gone_fishing", "Gone Fishing", "Caught 25 fish", "🎣", StampTier.UNCOMMON) { it.stat(Stats.FISH_CAUGHT) >= 25 },
        Stamp("veteran", "Veteran", "One year on MCTraveler", "🎖️", StampTier.EPIC) {
            it.now - it.passport.firstJoin >= 365L * 24 * 60 * 60 * 1000
        },
        // Granted at the moment they happen; evaluate never unlocks these.
        Stamp("ashes_to_ashes", "Ashes to Ashes", "Died in the Nether", "🔥", StampTier.RARE, eventOnly = true) { false },
        Stamp("into_the_void", "Into the Void", "Fell into the End void", "🕳️", StampTier.RARE, eventOnly = true) { false },
        Stamp("terminal_velocity", "Terminal Velocity", "Survived a 100 m fall", "🪂", StampTier.RARE, eventOnly = true) { false },
        Stamp("storm_chaser", "Storm Chaser", "Stood in a thunderstorm", "⛈️", StampTier.RARE, eventOnly = true) { false },
    )

    /** The stamps [evaluate] can ever unlock — everything but the event-granted ones. */
    private val EVALUATABLE: List<Stamp> = ALL.filterNot(Stamp::eventOnly)

    fun byId(id: String): Stamp? = ALL.firstOrNull { it.id == id }

    fun totalBounty(): Long = ALL.sumOf { it.tier.bounty }

    fun bountyFor(ids: Collection<String>): Long =
        ids.sumOf { id -> byId(id)?.tier?.bounty ?: 0L }

    /**
     * Whether every evaluatable stamp is already unlocked — the cheap "nothing
     * left to check" answer [PassportFeature.unlockStamps] uses to skip
     * building a context on every block break of a veteran miner.
     */
    fun allEvaluatableUnlocked(passport: Passport): Boolean =
        EVALUATABLE.all { it.id in passport.stamps }

    /** Adds newly satisfied stamps to passport.stamps (at = now) and returns them. */
    fun evaluate(context: StampContext): List<Stamp> =
        EVALUATABLE.filter {
            it.id !in context.passport.stamps &&
                it.unlocked(context) &&
                context.passport.stamps.putIfAbsent(it.id, context.now) == null
        }

    /** Grants an event-driven stamp directly; returns it only when newly earned. */
    fun grant(passport: Passport, id: String, at: Long): Stamp? {
        val stamp = byId(id) ?: return null
        return if (passport.stamps.putIfAbsent(id, at) == null) stamp else null
    }
}
