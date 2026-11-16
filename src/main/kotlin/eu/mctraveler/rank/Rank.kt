package eu.mctraveler.rank

import eu.mctraveler.text.Paint

/**
 * The rank ladder: `Newbie` (fresh account, cannot create regions) →
 * `Traveler` (the mod's long-standing default — every existing player before
 * this feature) → `Moderator` (staff: the moderation commands, once they switch
 * on `/admin`). Admins are operators, not a rank: out of admin mode they are plain
 * Travelers.
 *
 * Donator is not on this ladder. It is an extra a player of any rank can hold, which
 * adds a bigger region cap and sign/death-message markdown — see
 * [RankFeature.isDonator].
 *
 * Moderators wear the same green as Travelers (as admins do).
 *
 * Stored by [name] in [eu.mctraveler.persistence.PlayerStore.rank].
 */
enum class Rank(val label: String, val chatColor: Paint) {
    NEWBIE("Newbie", Paint.darkAqua),
    TRAVELER("Traveler", Paint.green),
    MODERATOR("Moderator", Paint.green),
    ;

    /** Whether this rank sits above [other] on the ladder. */
    fun outranks(other: Rank): Boolean = this > other

    companion object {
        /** The area cap of a region (`/rg start`/`end`/`extend`), in blocks. */
        const val REGION_CAP = 5000

        /** The same for a Donator. */
        const val DONATOR_REGION_CAP = 10000

        /** Every rank name, lowercase, in ladder order. */
        val COMMAND_NAMES: List<String> = entries.map { it.name.lowercase() }

        /** Parses a stored or typed name (case-insensitive), or null if it names no rank. */
        fun parse(value: String): Rank? = entries.find { it.name.equals(value, ignoreCase = true) }
    }
}
