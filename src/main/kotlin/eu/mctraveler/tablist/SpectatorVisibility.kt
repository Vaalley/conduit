package eu.mctraveler.tablist

import eu.mctraveler.mixin.ClientboundPlayerInfoUpdatePacketAccessor
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.vanish.VanishFeature
import kotlin.math.roundToInt
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType

/**
 * Issue #20: vanilla client code italicises/greys a Spectator's tab-list name for every
 * viewer, reacting purely to the `GameType` carried on their
 * [ClientboundPlayerInfoUpdatePacket] entry — a tell that lets a cheater notice
 * exactly when an admin is spectating them. Vanilla builds one identical packet (from the
 * live `GameType`) and broadcasts it unchanged to every connection, so hiding the tell from
 * non-admins means substituting a different packet for
 * their connection specifically — [OutboundPacketMixin][eu.mctraveler.mixin
 * .OutboundPacketMixin] does that per outgoing packet; this object decides *what* to
 * send.
 *
 * Two independent things get masked, since Creative and Spectator each have their own tell:
 * - **`GameType`**, to `SURVIVAL` — only for Spectator, the one vanilla's own tab list draws
 *   differently. `/gamemode` requires operator permission, so anyone actually reaching
 *   Spectator is already an admin ([RegionsFeature.isAdmin]).
 * - **[TabListFeature.HEALTH_OBJECTIVE]'s score** — to full health — for Spectator *or*
 *   Creative, since a bystander seeing an admin's real (possibly damaged, mid-combat) health
 *   while they investigate is exactly the kind of tell this exists to remove, and it carries
 *   no real gameplay meaning to that bystander regardless. Vanilla auto-tracks this score
 *   itself and broadcasts it identically to everyone, so masking it needs the same per-
 *   connection substitution as the tab entry — [maskScore].
 *
 * Neither ever applies to a viewer who is themselves an admin, or to the affected player's
 * own client: real Spectator noclip/free-camera is itself driven off the tab entry's
 * `GameType` when its UUID matches the client's own, so lying to the spectating admin's own
 * client would break the very thing they are trying to do.
 *
 * [ClientboundPlayerInfoUpdatePacket.Entry]'s canonical constructor is public (verified
 * with `javap` against the real 26.2 jar — this build ships no ProGuard mappings to check
 * a guess against otherwise), so a masked copy is built by calling it directly. The outer
 * packet has no such constructor for a ready-made entry list, so a masked packet is instead
 * built the long way: a normal, empty one via the public `(actions, Collection<ServerPlayer>)`
 * constructor, then [ClientboundPlayerInfoUpdatePacketAccessor] overwrites its entries — a
 * real Mixin-generated setter, not reflection.
 */
object SpectatorVisibility {

    /**
     * A copy of [packet] with every entry belonging to someone other than [viewer] masked
     * as described on the class, or null when nothing needs masking — [viewer] is an admin,
     * or no entry is a non-self Spectator.
     */
    @JvmStatic
    fun maskFor(viewer: ServerPlayer, packet: ClientboundPlayerInfoUpdatePacket): ClientboundPlayerInfoUpdatePacket? {
        if (RegionsFeature.isAdmin(viewer)) return null
        val entries = packet.entries()
        if (entries.isEmpty()) return null

        // A vanished player (issue #47) is simply not in a non-admin's list —
        // drop the entry before any spectator masking runs on what is left.
        val serverPlayers = viewer.level().server.playerList
        val visible = entries.filter { entry ->
            entry.profileId() == viewer.uuid ||
                serverPlayers.getPlayer(entry.profileId())?.let { !VanishFeature.isVanished(it) } ?: true
        }
        var changed = visible.size != entries.size

        val maskedEntries = visible.map { entry ->
            val maskGameMode = entry.gameMode() == GameType.SPECTATOR
            if (entry.profileId() == viewer.uuid || !maskGameMode) {
                entry
            } else {
                changed = true
                maskedEntry(entry)
            }
        }
        if (!changed) return null

        val fresh = ClientboundPlayerInfoUpdatePacket(packet.actions(), emptyList<ServerPlayer>())
        (fresh as ClientboundPlayerInfoUpdatePacketAccessor).`mctraveler$setEntries`(maskedEntries)
        return fresh
    }

    /** [entry] with its `GameType` swapped to `SURVIVAL`. */
    private fun maskedEntry(entry: ClientboundPlayerInfoUpdatePacket.Entry): ClientboundPlayerInfoUpdatePacket.Entry =
        ClientboundPlayerInfoUpdatePacket.Entry(
            entry.profileId(),
            entry.profile(),
            entry.listed(),
            entry.latency(),
            GameType.SURVIVAL,
            entry.displayName(),
            entry.showHat(),
            entry.listOrder(),
            entry.chatSession(),
        )

    /**
     * A copy of [packet] with its score set to full health, when [packet] is
     * [TabListFeature.HEALTH_OBJECTIVE]'s and its subject is a Spectator/Creative [viewer]
     * should not see the real value of — or null when nothing needs masking.
     */
    @JvmStatic
    fun maskScore(viewer: ServerPlayer, packet: ClientboundSetScorePacket): ClientboundSetScorePacket? {
        if (packet.objectiveName() != TabListFeature.HEALTH_OBJECTIVE) return null
        if (RegionsFeature.isAdmin(viewer)) return null
        val subject = viewer.level().server.playerList.getPlayerByName(packet.owner()) ?: return null
        if (subject === viewer) return null
        val subjectMode = subject.gameMode.gameModeForPlayer
        if (subjectMode != GameType.SPECTATOR && subjectMode != GameType.CREATIVE) return null
        return ClientboundSetScorePacket(
            packet.owner(),
            packet.objectiveName(),
            subject.maxHealth.roundToInt(),
            packet.display(),
            packet.numberFormat(),
        )
    }
}
