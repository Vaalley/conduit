package eu.mctraveler.rank

import eu.mctraveler.MCTraveler
import eu.mctraveler.geo.GeoIpFeature
import eu.mctraveler.persistence.PlayerStore
import eu.mctraveler.reloadable
import eu.mctraveler.text.Paint
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats

/**
 * The rank ladder ([Rank]): a Newbie is welcomed on their first ever join and
 * promoted to Traveler after an hour of play time; Donator is admin-granted
 * (`/rank set`, [RankCommands]).
 *
 * A player record that predates this feature (every existing player) has no
 * stored rank at all — [rankOf] reads that as Traveler, never Newbie, so
 * nobody already playing wakes up unable to make a region. Only a genuinely
 * new account (no player record whatsoever — [PlayerStore.hasRecord]) is
 * welcomed and starts a Newbie.
 */
object RankFeature {

    /** An hour of *play* time, not wall clock — [Stats.PLAY_TIME] only advances in-game. */
    const val PROMOTION_TICKS = 60 * 60 * 20L

    /** How often the promotion sweep looks at online Newbies. */
    private const val CHECK_INTERVAL_TICKS = 200L

    private data class PendingWelcome(val uuid: UUID, val country: String?)

    private val pendingWelcomes = ArrayDeque<PendingWelcome>()

    fun register() {
        ServerPlayConnectionEvents.JOIN.reloadable.register { handler, _, _ ->
            val player = handler.player
            if (!store().hasRecord(player.uuid)) {
                val address = handler.getRemoteAddress() as? InetSocketAddress
                val country = address?.address
                    ?.takeUnless(::isPrivateAddress)
                    ?.let(GeoIpFeature::lookup)
                    ?.name
                pendingWelcomes += PendingWelcome(player.uuid, country)
            }
        }
        ServerTickEvents.END_SERVER_TICK.reloadable.register(::onEndServerTick)
        CommandRegistrationCallback.EVENT.reloadable.register { dispatcher, _, _ ->
            RankCommands.register(dispatcher)
        }
    }

    // -- store-shaped: the behaviour --

    /**
     * [uuid]'s rank. A stored value wins outright; otherwise a brand-new
     * account (no record at all) is a Newbie-in-waiting (the join handler
     * persists it before this is ever asked), and an existing, rank-less
     * record is grandfathered in as a Traveler.
     */
    fun rankOf(store: PlayerStore, uuid: UUID): Rank =
        store.rank(uuid)?.let(Rank::parse)
            ?: if (store.hasRecord(uuid)) Rank.TRAVELER else Rank.NEWBIE

    // -- player-shaped façade --

    fun rankOf(player: ServerPlayer): Rank = rankOf(store(), player.uuid)

    fun setRank(player: ServerPlayer, rank: Rank) = store().setRank(player.uuid, rank.name)

    /**
     * The color a player's name is painted in chat and the tab list. Every
     * display-name build (vanilla's own tab-list refresh loop included) goes
     * through this, so an unreadable player record must never crash it — an
     * unrenderable name would be far worse than a wrong-colored one — hence
     * the fallback [MCTraveler.LOGGER] only warns about.
     */
    fun nameColor(player: ServerPlayer): Paint = try {
        rankOf(player).chatColor
    } catch (failure: Exception) {
        MCTraveler.LOGGER.warn("Could not read ${player.gameProfile.name}'s rank; showing the default color", failure)
        Rank.TRAVELER.chatColor
    }

    // -- the promotion + welcome loop --

    private fun onEndServerTick(server: MinecraftServer) {
        flushWelcomes(server)
        if (server.tickCount.toLong() % CHECK_INTERVAL_TICKS != 0L) return
        for (player in server.playerList.players) {
            checkPromotion(player)
        }
    }

    private fun flushWelcomes(server: MinecraftServer) {
        while (true) {
            val pending = pendingWelcomes.removeFirstOrNull() ?: return
            val player = server.playerList.getPlayer(pending.uuid) ?: continue
            val store = store()
            // A record may already exist by now (e.g. a race with another
            // login path) — never overwrite it, and never welcome twice.
            if (store.hasRecord(pending.uuid)) continue
            store.setRank(pending.uuid, Rank.NEWBIE.name)
            server.playerList.broadcastSystemMessage(welcomeLine(player.gameProfile.name, pending.country), false)
        }
    }

    private fun checkPromotion(player: ServerPlayer) {
        val store = store()
        if (rankOf(store, player.uuid) != Rank.NEWBIE) return
        if (playTimeTicks(player) < PROMOTION_TICKS) return
        store.setRank(player.uuid, Rank.TRAVELER.name)
        player.sendSystemMessage(promotionMessage())
    }

    private fun playTimeTicks(player: ServerPlayer): Int = player.stats.getValue(Stats.CUSTOM, Stats.PLAY_TIME)

    /** `Welcome <name> from <Country> to MCTraveler!` — country omitted when unknown. */
    private fun welcomeLine(name: String, country: String?): Component = Paint.gray(
        "Welcome ",
        Paint.darkAqua(name),
        country?.let { Paint.gray(" from ", Paint.green(it)) },
        " to MCTraveler!",
    )

    /** The Newbie → Traveler promotion message, `/region start` click-to-run. */
    private fun promotionMessage(): Component = Paint.gray(
        "You can now create ", Paint.green("regions"), " to protect your stuff!",
        "\n",
        "Get started using ", Paint.green.underline.runs("/region start")("/region start"),
    )

    private fun isPrivateAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress ||
            address.isSiteLocalAddress ||
            address.isLinkLocalAddress ||
            address.isAnyLocalAddress

    private fun store(): PlayerStore =
        checkNotNull(MCTraveler.persistence) { "Ranks need the Persistence service" }.players
}
