package eu.mctraveler.cosmetic

import eu.mctraveler.MCTraveler
import eu.mctraveler.rank.Rank
import eu.mctraveler.rank.RankFeature
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

object NameCosmetics {
    private data class Key(
        val name: String,
        val tag: String?,
        val color: String?,
        val gradient: Pair<String, String>?,
        val rank: String,
    )

    private data class Cached(val key: Key, val component: Component)

    private val cache = ConcurrentHashMap<UUID, Cached>()
    private val unreadableRecords = ConcurrentHashMap.newKeySet<UUID>()

    fun forPlayer(player: ServerPlayer): Component {
        val persistence = checkNotNull(MCTraveler.persistence) { "Name cosmetics need the Persistence service" }
        val players = persistence.players
        val rank = runCatching { RankFeature.rankOf(player) }.getOrDefault(Rank.TRAVELER)
        val cosmetics = if (player.uuid in unreadableRecords) {
            Triple(null, null, null)
        } else {
            runCatching {
                Triple(
                    players.nameTag(player.uuid),
                    players.nameColor(player.uuid),
                    players.nameGradient(player.uuid),
                )
            }.getOrElse { failure ->
                unreadableRecords += player.uuid
                MCTraveler.LOGGER.warn(
                    "Could not read ${player.gameProfile.name}'s name cosmetics; showing defaults",
                    failure,
                )
                Triple(null, null, null)
            }
        }
        val key = Key(
            player.gameProfile.name,
            cosmetics.first,
            cosmetics.second,
            cosmetics.third,
            rank.name,
        )
        cache[player.uuid]?.takeIf { it.key == key }?.let { return it.component }
        val component = NameStyle.render(
            player.gameProfile.name,
            NameStyle.Style(
                key.tag,
                key.color?.let(NameStyle::parseHex),
                key.gradient?.let { (from, to) ->
                    val parsedFrom = NameStyle.parseHex(from)
                    val parsedTo = NameStyle.parseHex(to)
                    if (parsedFrom == null || parsedTo == null) null else parsedFrom to parsedTo
                },
                rank == Rank.DONATOR,
                RankFeature.nameColor(player),
            ),
        )
        cache[player.uuid] = Cached(key, component)
        return component
    }

    /**
     * [player]'s styled name ([forPlayer]) when it carries anything worth showing — a Donator's
     * gold, or a tag, colour or gradient they set — and [plain] otherwise. Chat lines that are not
     * the chat message itself (private messages, region chat, join/leave/away announcements) use
     * this, so a name looks the same wherever it appears without changing what an ordinary
     * player's lines look like. Never throws: a name that cannot be styled is shown plain.
     */
    fun nameOr(player: ServerPlayer, plain: () -> Component): Component = runCatching {
        val players = MCTraveler.persistence?.players
        val hasCosmetics = players != null &&
            (players.nameTag(player.uuid) != null ||
                players.nameColor(player.uuid) != null ||
                players.nameGradient(player.uuid) != null)
        if (RankFeature.rankOf(player) == Rank.DONATOR || hasCosmetics) forPlayer(player) else plain()
    }.getOrElse { plain() }

    fun invalidate(uuid: UUID) {
        cache.remove(uuid)
        unreadableRecords.remove(uuid)
    }
}
