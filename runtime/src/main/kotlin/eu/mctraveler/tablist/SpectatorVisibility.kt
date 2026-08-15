package eu.mctraveler.tablist

import eu.mctraveler.mixin.ClientboundPlayerInfoUpdatePacketAccessor
import eu.mctraveler.region.RegionsFeature
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType

/**
 * Issue #20: vanilla client code italicises/greys a Spectator's tab-list name for every
 * viewer, reacting purely to the `GameType` carried on their
 * [ClientboundPlayerInfoUpdatePacket] entry. That is a tell — a cheater watching the tab
 * list can notice exactly when an admin switches to Spectator to watch them. Vanilla builds
 * one identical packet (from the live `GameType`) and broadcasts it unchanged to every
 * connection, so hiding the tell from non-admins means substituting a different packet for
 * their connection specifically — [SpectatorVisibilityMixin][eu.mctraveler.mixin
 * .SpectatorVisibilityMixin] does that per outgoing packet; this object decides *what* to
 * send.
 *
 * Two independent things get masked, since Creative and Spectator each have their own tell:
 * - **`GameType`**, to `SURVIVAL` — only for Spectator, the one vanilla's own tab list draws
 *   differently. `/gamemode` requires operator permission, so anyone actually reaching
 *   Spectator is already an admin ([RegionsFeature.isAdmin]).
 * - **The tab entry's hearts** (part of its display name — see [TabListFeature.hearts]), to
 *   full with no Absorption — for Spectator *or* Creative, since a bystander seeing an
 *   admin's real (possibly damaged, mid-combat) health while they investigate is exactly
 *   the kind of tell this exists to remove, and it carries no real gameplay meaning to that
 *   bystander regardless.
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
     * or no entry is a non-self Spectator/Creative.
     */
    @JvmStatic
    fun maskFor(viewer: ServerPlayer, packet: ClientboundPlayerInfoUpdatePacket): ClientboundPlayerInfoUpdatePacket? {
        if (RegionsFeature.isAdmin(viewer)) return null
        val entries = packet.entries()
        if (entries.isEmpty()) return null

        var changed = false
        val maskedEntries = entries.map { entry ->
            val maskGameMode = entry.gameMode() == GameType.SPECTATOR
            val maskHearts = entry.gameMode() == GameType.SPECTATOR || entry.gameMode() == GameType.CREATIVE

            if (entry.profileId() == viewer.uuid || !(maskGameMode || maskHearts)) {
                entry
            } else {
                val fullHeartsName = if (maskHearts) {
                    viewer.level().server.playerList.getPlayer(entry.profileId())
                        ?.let(TabListFeature::displayNameWithFullHearts)
                } else {
                    null
                }
                if (!maskGameMode && fullHeartsName == null) {
                    entry
                } else {
                    changed = true
                    maskedEntry(entry, maskGameMode, fullHeartsName)
                }
            }
        }
        if (!changed) return null

        val fresh = ClientboundPlayerInfoUpdatePacket(packet.actions(), emptyList<ServerPlayer>())
        (fresh as ClientboundPlayerInfoUpdatePacketAccessor).`mctraveler$setEntries`(maskedEntries)
        return fresh
    }

    /** [entry] with [GameType.SURVIVAL] swapped in iff [maskGameMode], and [fullHeartsName] iff non-null. */
    private fun maskedEntry(
        entry: ClientboundPlayerInfoUpdatePacket.Entry,
        maskGameMode: Boolean,
        fullHeartsName: Component?,
    ): ClientboundPlayerInfoUpdatePacket.Entry = ClientboundPlayerInfoUpdatePacket.Entry(
        entry.profileId(),
        entry.profile(),
        entry.listed(),
        entry.latency(),
        if (maskGameMode) GameType.SURVIVAL else entry.gameMode(),
        fullHeartsName ?: entry.displayName(),
        entry.showHat(),
        entry.listOrder(),
        entry.chatSession(),
    )
}
