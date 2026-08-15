package eu.mctraveler.gametest

import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.scores.DisplaySlot

/**
 * The sidebar a player's client would be rendering, rebuilt from the scoreboard
 * packets the server has actually sent them — the closest headless stand-in for
 * "what is on their screen". Scoreboard packets are incremental, so [refresh]
 * folds every packet sent since the last call into the running picture.
 */
class SidebarView(private val player: ServerPlayer) {

    private class Entry(val display: Component?, val score: Int)

    private val entries = LinkedHashMap<String, Entry>()
    private var objectiveTitle: Component = Component.empty()
    private var shownObjective: String? = null

    /** How many times the client has been told to create the sidebar objective. */
    var objectiveAdditions: Int = 0
        private set

    /** Whether the region sidebar is on screen. */
    val visible: Boolean get() = shownObjective == "region"

    /** The sidebar's heading, as last set. */
    val title: Component get() = objectiveTitle

    /** The sidebar's lines, top to bottom — highest score first, as the client orders them. */
    val lines: List<Component>
        get() = entries.values.sortedByDescending(Entry::score).map { it.display ?: Component.empty() }

    /** Applies every scoreboard packet sent since the last call. Returns this view. */
    fun refresh(): SidebarView {
        for (packet in PacketCapture.drain(player)) {
            when (packet) {
                is ClientboundSetObjectivePacket -> when (packet.method) {
                    ClientboundSetObjectivePacket.METHOD_REMOVE -> {
                        entries.clear()
                        objectiveTitle = Component.empty()
                    }
                    else -> {
                        if (packet.method == ClientboundSetObjectivePacket.METHOD_ADD) objectiveAdditions++
                        objectiveTitle = packet.displayName
                    }
                }
                is ClientboundSetScorePacket ->
                    entries[packet.owner()] = Entry(packet.display().orElse(null), packet.score())
                is ClientboundResetScorePacket -> entries.remove(packet.owner())
                is ClientboundSetDisplayObjectivePacket ->
                    if (packet.slot == DisplaySlot.SIDEBAR) {
                        shownObjective = packet.objectiveName?.takeIf { it.isNotEmpty() }
                    }
            }
        }
        return this
    }
}
