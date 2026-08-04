package eu.mctraveler.tablist

import eu.mctraveler.MCTraveler
import eu.mctraveler.region.RegionsFeature
import java.lang.reflect.Constructor
import java.lang.reflect.ParameterizedType
import java.lang.reflect.RecordComponent
import java.util.UUID
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
 * The entry type and the packet's `(actions, entries)` constructor are narrower than
 * public, and this build ships no ProGuard mappings to hand-verify a descriptor against
 * (26.1+ is remap-free — see `docs/dev-loop.md`), so both are found by runtime shape —
 * a record component typed `UUID`, one typed `GameType`, one typed `Component`, a
 * constructor whose second parameter is generically a `Collection` of the entry type —
 * rather than a guessed name. Every lookup fails open: if the shape it finds ever stops
 * matching, [maskFor] returns null and the real, unmasked packet goes out — today's
 * behaviour, not a crash.
 */
object SpectatorVisibility {

    private var loggedFailure = false

    private var resolved = false
    private var resolutionFailed = false
    private var entryComponents: Array<RecordComponent>? = null
    private var entryConstructor: Constructor<*>? = null
    private var gameModeIndex = -1
    private var profileIdIndex = -1

    /** -1 when not found: hearts-masking degrades gracefully, gameMode-masking still works. */
    private var displayNameIndex = -1
    private var packetConstructor: Constructor<*>? = null

    /**
     * A copy of [packet] with every entry belonging to someone other than [viewer] masked
     * as described on the class, or null when nothing needs masking — [viewer] is an admin,
     * no entry is a non-self Spectator/Creative, or the reflective lookups above could not
     * find what they need.
     */
    @JvmStatic
    fun maskFor(viewer: ServerPlayer, packet: ClientboundPlayerInfoUpdatePacket): ClientboundPlayerInfoUpdatePacket? {
        if (RegionsFeature.isAdmin(viewer)) return null
        val entries = packet.entries()
        if (entries.isEmpty() || !resolve(entries)) return null

        val components = entryComponents ?: return null
        val ctor = entryConstructor ?: return null
        val gmIndex = gameModeIndex
        val pidIndex = profileIdIndex
        val dnIndex = displayNameIndex

        var changed = false
        val maskedEntries = entries.map { entry ->
            val profileId = components[pidIndex].accessor.invoke(entry) as UUID
            val gameMode = components[gmIndex].accessor.invoke(entry) as GameType
            val maskGameMode = gameMode == GameType.SPECTATOR
            val maskHearts = dnIndex >= 0 && (gameMode == GameType.SPECTATOR || gameMode == GameType.CREATIVE)

            if (profileId == viewer.uuid || !(maskGameMode || maskHearts)) {
                entry
            } else {
                val fullHeartsName = if (maskHearts) {
                    viewer.level().server.playerList.getPlayer(profileId)
                        ?.let(TabListFeature::displayNameWithFullHearts)
                } else {
                    null
                }
                if (!maskGameMode && fullHeartsName == null) {
                    entry
                } else {
                    changed = true
                    val args = components.mapIndexed { index, component ->
                        when {
                            index == gmIndex && maskGameMode -> GameType.SURVIVAL
                            index == dnIndex && fullHeartsName != null -> fullHeartsName
                            else -> component.accessor.invoke(entry)
                        }
                    }.toTypedArray()
                    runCatching { ctor.newInstance(*args) }
                        .getOrElse { return fail("could not build a masked tab-list entry: ${it.message}") }
                }
            }
        }
        if (!changed) return null

        val packetCtor = packetConstructor ?: return null
        return runCatching {
            packetCtor.newInstance(packet.actions(), maskedEntries) as ClientboundPlayerInfoUpdatePacket
        }.getOrElse { fail("could not build a masked tab-list packet: ${it.message}") }
    }

    private fun fail(reason: String): ClientboundPlayerInfoUpdatePacket? {
        if (!loggedFailure) {
            loggedFailure = true
            MCTraveler.LOGGER.warn(
                "SpectatorVisibility: $reason — Spectator/Creative will show real state to every viewer " +
                    "until the next restart.",
            )
        }
        return null
    }

    /** Resolves every reflective handle exactly once, from a real entry's own runtime shape. */
    private fun resolve(entries: List<*>): Boolean {
        if (resolved) return !resolutionFailed
        resolved = true

        val sample = entries[0]
        if (sample == null) {
            resolutionFailed = true
            fail("a null tab-list entry")
            return false
        }
        val entryClass = sample.javaClass
        val components = entryClass.recordComponents
        if (components == null || components.isEmpty()) {
            resolutionFailed = true
            fail("the tab-list entry type is not a record")
            return false
        }

        val gmIndex = components.indexOfFirst { it.type == GameType::class.java }
        val pidIndex = components.indexOfFirst { it.type == UUID::class.java }
        if (gmIndex < 0 || pidIndex < 0) {
            resolutionFailed = true
            fail("the tab-list entry has no GameType/UUID record component")
            return false
        }
        val dnIndex = components.indexOfFirst { it.type == Component::class.java }

        val ctor = runCatching {
            entryClass.getDeclaredConstructor(*components.map { it.type }.toTypedArray())
                .apply { isAccessible = true }
        }.getOrNull()
        if (ctor == null) {
            resolutionFailed = true
            fail("no canonical constructor on the tab-list entry type")
            return false
        }

        val packetCtor = ClientboundPlayerInfoUpdatePacket::class.java.declaredConstructors.firstOrNull { candidate ->
            val params = candidate.parameterTypes
            params.size == 2 &&
                Set::class.java.isAssignableFrom(params[0]) &&
                Collection::class.java.isAssignableFrom(params[1]) &&
                (candidate.genericParameterTypes.getOrNull(1) as? ParameterizedType)
                    ?.actualTypeArguments?.singleOrNull() == entryClass
        }?.apply { isAccessible = true }
        if (packetCtor == null) {
            resolutionFailed = true
            fail("no (actions, entries) constructor on the tab-list packet type")
            return false
        }

        components.forEach { it.accessor.isAccessible = true }
        entryComponents = components
        entryConstructor = ctor
        gameModeIndex = gmIndex
        profileIdIndex = pidIndex
        displayNameIndex = dnIndex
        packetConstructor = packetCtor
        return true
    }
}
