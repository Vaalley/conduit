package eu.mctraveler.motd

import eu.mctraveler.text.Paint
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.status.ServerStatus
import net.minecraft.server.MinecraftServer
import net.minecraft.server.players.NameAndId

/**
 * The server-list presence (Portal features/MotdFeature.ts + the main.ts status
 * response): the exact two-line MOTD and a first-12 player sample, decorated onto the
 * status the vanilla server builds. Everything else — live player count, the real
 * max-players, the standard server-icon.png favicon, the honest enforcesSecureChat,
 * the real version — is passed through untouched from vanilla.
 *
 * Hooked into `MinecraftServer.buildServerStatus` by `eu.mctraveler.mixin.MinecraftServerMixin`.
 */
object Motd {

    /** Up to this many players appear in the server-list sample (Portal main.ts). */
    const val SAMPLE_SIZE = 12

    /**
     * The Portal's exact two MOTD lines: the play address in green with MCTraveler
     * bold, then the anniversary line in gray — spacing verbatim.
     */
    val DESCRIPTION: Component = Paint(
        Paint.green("                  play.", Paint.bold("MCTraveler"), ".eu"),
        "\n",
        Paint.gray("       Celebrating 13 years of vanilla survival"),
    )

    /**
     * Decorates the [status] the live [server] just built: the roster is the online
     * players in join order, honoring each player's listing opt-out (anonymous entry)
     * and the hide-online-players setting (empty sample).
     */
    fun decorate(status: ServerStatus, server: MinecraftServer): ServerStatus =
        decorate(status, roster(server))

    /**
     * Decorates a vanilla-built [status]: the description becomes [DESCRIPTION] and the
     * player sample the first [SAMPLE_SIZE] of [roster] (the online players, in join
     * order, already anonymized where a player opts out of listing). The vanilla
     * max/online counts, version, favicon, and secure-chat advertisement are preserved.
     */
    fun decorate(status: ServerStatus, roster: List<NameAndId>): ServerStatus =
        ServerStatus(
            DESCRIPTION,
            status.players().map { ServerStatus.Players(it.max(), it.online(), roster.take(SAMPLE_SIZE)) },
            status.version(),
            status.favicon(),
            status.enforcesSecureChat(),
        )

    private fun roster(server: MinecraftServer): List<NameAndId> =
        if (server.hidesOnlinePlayers()) emptyList()
        else server.playerList.players.map { player ->
            if (player.allowsListing()) player.nameAndId()
            else MinecraftServer.ANONYMOUS_PLAYER_PROFILE
        }
}
