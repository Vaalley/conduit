package eu.mctraveler.command

import eu.mctraveler.MCTraveler
import eu.mctraveler.rank.Rank
import eu.mctraveler.rank.RankFeature
import eu.mctraveler.region.RegionsFeature
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
 */
enum class Audience {
    /** Everybody. */
    ALL,

    /** Anyone who is not a Newbie; admins always pass. */
    TRAVELER,

    /** Donators; admins always pass. */
    DONATOR,

    /** Admins (vanilla operators). */
    ADMIN,
    ;

    /** The pure decision: whether an [admin] with the given [rank] belongs to this audience. */
    fun permits(admin: Boolean, rank: Rank): Boolean = when (this) {
        ALL -> true
        TRAVELER -> admin || rank != Rank.NEWBIE
        DONATOR -> admin || rank == Rank.DONATOR
        ADMIN -> admin
    }

    /** Whether [player] belongs to this audience; never throws. */
    fun permits(player: ServerPlayer): Boolean {
        if (this == ALL) return true
        val admin = try {
            RegionsFeature.isAdmin(player)
        } catch (failure: Exception) {
            warn(player, failure)
            false
        }
        if (this == ADMIN) return admin
        if (admin) return true
        // An unreadable player record reads as Traveler, the way RankFeature.nameColor does.
        val rank = try {
            RankFeature.rankOf(player)
        } catch (failure: Exception) {
            warn(player, failure)
            Rank.TRAVELER
        }
        return permits(admin = false, rank = rank)
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
