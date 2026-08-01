package eu.mctraveler.crystal

import eu.mctraveler.text.Paint
import eu.mctraveler.worlds.Landing
import java.util.UUID
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerPlayer

/**
 * Teleport requests between players (spec User Stories 34-35; Nucleus's
 * `teleportationRequests` and `TeleportationCrystalListener.onCommand`).
 *
 * Clicking a head in the crystal's player menu spends a point of energy and
 * asks someone to let you come to them. They accept by clicking the message,
 * which runs [ACCEPT_COMMAND] — a command that exists nowhere in the Brigadier
 * tree, so it never reaches a client's tab completion (spec deviation 8). The
 * packet carrying it is intercepted before vanilla can parse it
 * ([eu.mctraveler.mixin.CrystalAcceptCommandMixin]), which is also why an
 * unknown-command error never reaches the player.
 *
 * The energy is spent on *asking*, not on arriving — the crystal's lore says so
 * ("costs even if they don't accept").
 */
object CrystalRequests {

    /**
     * How long a request stands. Nucleus used five wall-clock minutes; this is
     * the same span in server ticks, per the house all-timing-is-ticks rule
     * (spec deviation 4).
     */
    const val TIMEOUT_TICKS = 6000

    /** The unregistered command that accepts a request. */
    const val ACCEPT_COMMAND = "teleportation-crystal-accept"

    /** Who a player asked, and when. */
    private class Request(val target: UUID, var createdAtTick: Int)

    /** Outstanding requests, keyed by the player who asked. One each, as in Nucleus. */
    private val requests = HashMap<UUID, Request>()

    /**
     * [requester] clicked [head] (spec story 34). A target who has left in the
     * meantime costs nothing; otherwise the energy goes whether or not they
     * ever answer.
     */
    fun send(requester: ServerPlayer, head: CrystalMenu.Head) {
        val server = requester.level().server
        val target = server.playerList.getPlayer(head.uuid)
        if (target == null) {
            requester.sendSystemMessage(notOnline(head.name))
            return
        }
        requests[requester.uuid] = Request(target.uuid, server.tickCount)
        target.sendSystemMessage(Paint.info(invitation(requester.gameProfile.name)))
        CrystalEnergy.modify(requester, -1)
        requester.sendSystemMessage(
            Paint.success("One energy used to send request to ", Paint.green(head.name)),
        )
    }

    /**
     * The whole clickable line the target sees. The click event sits on the
     * root, so anywhere on the line accepts — Nucleus set it on its builder,
     * which had the same effect.
     */
    private fun invitation(requesterName: String): MutableComponent =
        Paint(
            Paint.aqua(requesterName),
            " wants to teleport to you - click ",
            Paint.aqua("here"),
            " to accept",
        ).withStyle { style ->
            style.withClickEvent(ClickEvent.RunCommand("/$ACCEPT_COMMAND $requesterName"))
        }

    /**
     * True if [command] — a command line as the packet carries it, with or
     * without its leading slash — is the hidden accept command. Safe from any
     * thread: the packet hook asks this before deciding to hop to the server
     * thread.
     */
    @JvmStatic
    fun isAcceptCommand(command: String): Boolean {
        val trimmed = command.removePrefix("/")
        return trimmed == ACCEPT_COMMAND || trimmed.startsWith("$ACCEPT_COMMAND ")
    }

    /**
     * Runs the accept command for [acceptor] (spec story 35), validating in
     * Nucleus's order. Server thread only.
     *
     * The arity check is deliberately silent: Nucleus cancelled the command
     * event and returned, so a bare `/teleportation-crystal-accept` produced no
     * output at all — not even vanilla's unknown-command error, since the
     * command was never registered.
     */
    @JvmStatic
    fun accept(acceptor: ServerPlayer, command: String) {
        val args = command.removePrefix("/").split(" ")
        if (args.size != 2) return
        val requesterName = args[1]
        val server = acceptor.level().server
        val requester = exactPlayer(server, requesterName)
        if (requester == null) {
            acceptor.sendSystemMessage(notOnline(requesterName))
            return
        }
        val request = requests[requester.uuid]
        if (request == null || request.target != acceptor.uuid) {
            acceptor.sendSystemMessage(Paint.error("No request found"))
            return
        }
        // Consumed either way: a request that has timed out is spent by the
        // attempt, exactly as in Nucleus.
        requests.remove(requester.uuid)
        if (hasTimedOut(request.createdAtTick, server.tickCount)) {
            acceptor.sendSystemMessage(Paint.error("Request timed out"))
            return
        }
        Landing.of(acceptor).send(requester)
        requester.sendSystemMessage(
            Paint.info(Paint.aqua(acceptor.gameProfile.name), " has accepted your request"),
        )
        acceptor.sendSystemMessage(Paint.success("Request accepted"))
    }

    /**
     * Drops everything [uuid] is party to — the request they made, and any
     * request aimed at them.
     *
     * Nucleus dropped only the first, and leaned on a `WeakHashMap` to collect
     * the rest once the player object died. A plain map has no such collector,
     * and a request whose target has gone can never be accepted anyway, so both
     * directions go here.
     */
    fun forget(uuid: UUID) {
        requests.remove(uuid)
        requests.values.removeIf { it.target == uuid }
    }

    /** Whether a request made at [createdAtTick] has lapsed by [now]. */
    fun hasTimedOut(createdAtTick: Int, now: Int): Boolean = now - createdAtTick > TIMEOUT_TICKS

    /** Test seam: forget every outstanding request. */
    fun clear() {
        requests.clear()
    }

    /**
     * Test seam: back-dates every outstanding request by [ticks], so a test can
     * reach the timeout without sitting through five minutes of it.
     */
    fun backdate(ticks: Int) {
        for (request in requests.values) request.createdAtTick -= ticks
    }
}
