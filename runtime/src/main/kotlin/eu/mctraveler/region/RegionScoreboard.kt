package eu.mctraveler.region

import eu.mctraveler.text.Paint
import java.util.Optional
import java.util.UUID
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.BlankFormat
import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.criteria.ObjectiveCriteria

/**
 * The region sidebar — the residents board a player sees while standing in a
 * region (inventory §2.8).
 *
 * Each player gets their own board, drawn with scoreboard packets addressed to
 * them alone; the server's shared [Scoreboard] is never touched. It has to be
 * per-player — the board names one region, only while its occupant is inside,
 * with the reader's own name picked out in white — and going straight to the
 * connection also leaves vanilla `/scoreboard` entirely alone. The Portal
 * crafted the same packets because a proxy had nothing else; here it is the
 * simplest thing that can work.
 *
 * The objective is created once per session (a client rejects a second
 * objective of the same name). The Portal re-created it on every backend
 * server switch to survive the client-side scoreboard reset those caused;
 * one server means one connection means one create.
 */
object RegionScoreboard {

    /** The objective name, kept from the Portal — it is visible in `/scoreboard`-less clients only. */
    private const val OBJECTIVE = "region"

    private const val RESIDENTS_ROW = "@residents"
    private const val SEPARATOR_ROW = "@break"

    /** Titles and names are cut to the width the sidebar can show. */
    private const val MAX_WIDTH = 20

    private val SEPARATOR = " ".repeat(30)

    /** One line of the board: its score-holder id, what it reads, and where it sorts. */
    private class Row(val id: String, val text: Component, val score: Int)

    /** The rows on each player's board, so a redraw knows which ones to retract. */
    private val drawn = HashMap<UUID, List<String>>()

    /** Players whose client has been told the objective exists. */
    private val created = HashSet<UUID>()

    /** Never registered anywhere: it exists only to shape the objective packets. */
    private val packetScoreboard = Scoreboard()

    /**
     * Draws [region]'s board for [player] — on entry and on every later change
     * to what it says. A region flagged `NO_SCOREBOARD` draws nothing and takes
     * any previous board away.
     */
    fun draw(player: ServerPlayer, region: Region) {
        if ("NO_SCOREBOARD" in region.flags) {
            hide(player)
            return
        }
        val connection = player.connection ?: return
        if (created.add(player.uuid)) {
            connection.send(
                ClientboundSetObjectivePacket(objective(Component.empty()), ClientboundSetObjectivePacket.METHOD_ADD),
            )
        }
        connection.send(
            ClientboundSetObjectivePacket(
                objective(Paint.green.bold(region.title.take(MAX_WIDTH))),
                ClientboundSetObjectivePacket.METHOD_CHANGE,
            ),
        )

        val rows = rowsOf(player, region)
        val ids = rows.map(Row::id)
        for (gone in drawn[player.uuid].orEmpty()) {
            if (gone !in ids) connection.send(ClientboundResetScorePacket(gone, OBJECTIVE))
        }
        for (row in rows) {
            connection.send(
                ClientboundSetScorePacket(
                    row.id,
                    OBJECTIVE,
                    row.score,
                    Optional.of(row.text),
                    // The scores only order the lines; the numbers stay hidden.
                    Optional.of(BlankFormat.INSTANCE),
                ),
            )
        }
        drawn[player.uuid] = ids
        connection.send(ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective(Component.empty())))
    }

    /** Takes the board off [player]'s screen, if it is on it. */
    fun hide(player: ServerPlayer) {
        val rows = drawn.remove(player.uuid) ?: return
        val connection = player.connection ?: return
        for (row in rows) connection.send(ClientboundResetScorePacket(row, OBJECTIVE))
        connection.send(ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null))
    }

    /** Drops a player's board state when their session ends. */
    fun forget(uuid: UUID) {
        drawn.remove(uuid)
        created.remove(uuid)
    }

    /** Drops every player's board state (the server is going down). */
    fun forgetAll() {
        drawn.clear()
        created.clear()
    }

    /**
     * The board's lines, top-first once the client sorts them by score: the
     * separator, then `Residents`, then the members — the reader in white and
     * everyone else in gray. Members nothing can name are left out.
     */
    private fun rowsOf(player: ServerPlayer, region: Region): List<Row> {
        val rows = mutableListOf<Row>()
        val memberCount = region.members.size
        rows += Row(RESIDENTS_ROW, Paint.bold("Residents"), memberCount)
        rows += Row(SEPARATOR_ROW, Paint.darkGray.strikethrough(SEPARATOR), memberCount + 1)

        val server = player.level().server
        var place = 0
        for (uuid in region.members) {
            val name = RegionsFeature.usernameFor(server, uuid) ?: continue
            val ink = if (uuid == player.uuid) Paint.white else Paint.gray
            rows += Row(uuid.toString(), ink(name.take(MAX_WIDTH)), place)
            place++
        }
        return rows
    }

    private fun objective(title: Component): Objective = Objective(
        packetScoreboard,
        OBJECTIVE,
        ObjectiveCriteria.DUMMY,
        title,
        ObjectiveCriteria.RenderType.INTEGER,
        false,
        null,
    )
}
