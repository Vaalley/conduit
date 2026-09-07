package eu.mctraveler.rank

import eu.mctraveler.text.Paint

/**
 * The rank ladder: `Newbie` (fresh account, cannot create regions) →
 * `Traveler` (the mod's long-standing default — every existing player before
 * this feature) → `Donator` (a bigger region cap, sign/death-message markdown).
 *
 * Stored by [name] in [eu.mctraveler.persistence.PlayerStore.rank].
 */
enum class Rank(val label: String, val chatColor: Paint, val regionAreaCap: Int) {
    NEWBIE("Newbie", Paint.darkAqua, RegionCaps.DEFAULT),
    TRAVELER("Traveler", Paint.green, RegionCaps.DEFAULT),
    DONATOR("Donator", Paint.gold, RegionCaps.DONATOR),
    ;

    companion object {
        /** Every rank name, lowercase, in ladder order — `/rank set`'s tab completion. */
        val COMMAND_NAMES: List<String> = entries.map { it.name.lowercase() }

        /** Parses a stored or typed name (case-insensitive), or null if it names no rank. */
        fun parse(value: String): Rank? = entries.find { it.name.equals(value, ignoreCase = true) }
    }
}

/** The region-footprint cap (`/rg start`/`end`/`extend`'s area limit) per rank. */
private object RegionCaps {
    const val DEFAULT = 5000
    const val DONATOR = 10000
}
