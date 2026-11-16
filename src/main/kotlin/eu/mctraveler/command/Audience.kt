package eu.mctraveler.command

import eu.mctraveler.MCTraveler
import eu.mctraveler.admin.StaffAccess
import eu.mctraveler.rank.Rank
import eu.mctraveler.rank.RankFeature
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer

/**
 * Who a command is for. A root command that is wholly restricted to an audience
 * puts [gate] in its `requires`, which is what hides it from the client's `/`
 * suggestions (the command tree the server syncs) *and* from `/help`, and makes
 * running it by hand answer like an unknown command.
 *
 * Commands with a public part and a restricted part (`/region`'s `bounds`,
 * `/balance set`) stay public at the root and keep gating the restricted part in
 * the command body; only the help catalog hides those options.
 *
 * Sources that are not a player (the console, command blocks) pass every gate,
 * the way [eu.mctraveler.moderation.ModerationFeature]'s gate always did.
 *
 * Staff only count once they have switched on `/admin`: an operator who has not
 * is treated as a Donator.
 */
enum class Audience {
    /** Everybody. */
    ALL,

    /** Anyone who is not a Newbie; staff in admin mode always pass. */
    TRAVELER,

    /** Donators and above; staff in admin mode always pass. */
    DONATOR,

    /** Moderators and admins with admin mode on. */
    STAFF,

    /** Admins (operators) with admin mode on — not moderators. */
    ADMIN,
    ;

    /**
     * The pure decision: whether a player belongs to this audience, given whether they
     * are a full [admin] (an operator in admin mode), whether they are [staff] in admin
     * mode (admins count), and their effective [rank].
     */
    fun permits(admin: Boolean, rank: Rank, staff: Boolean = admin): Boolean = when (this) {
        ALL -> true
        TRAVELER -> staff || admin || rank != Rank.NEWBIE
        DONATOR -> staff || admin || rank.hasDonatorPerks
        STAFF -> staff || admin
        ADMIN -> admin
    }

    /** Whether [player] belongs to this audience; never throws. */
    fun permits(player: ServerPlayer): Boolean {
        if (this == ALL) return true
        val admin: Boolean
        val staff: Boolean
        try {
            admin = StaffAccess.isFullAdmin(player)
            staff = StaffAccess.isActive(player)
        } catch (failure: Exception) {
            warn(player, failure)
            return false
        }
        if (this == ADMIN) return admin
        if (this == STAFF) return staff
        if (staff) return true
        // An unreadable player record reads as Traveler, the way RankFeature.nameColor does.
        val rank = try {
            RankFeature.effectiveRank(player)
        } catch (failure: Exception) {
            warn(player, failure)
            Rank.TRAVELER
        }
        return permits(admin = false, rank = rank, staff = false)
    }

    /**
     * The predicate for `Commands.literal(..).requires(..)`. It runs while the
     * command tree is synced to a client, so it must never throw.
     */
    val gate: (CommandSourceStack) -> Boolean = { source ->
        source.player?.let(::permits) ?: true
    }

    private fun warn(player: ServerPlayer, failure: Exception) {
        MCTraveler.LOGGER.warn("Could not check ${player.gameProfile.name} against the $name command audience", failure)
    }
}
