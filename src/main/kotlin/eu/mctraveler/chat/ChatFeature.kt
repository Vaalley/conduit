package eu.mctraveler.chat

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import eu.mctraveler.geo.GeoIpFeature
import eu.mctraveler.region.RegionTracker
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import eu.mctraveler.vanish.VanishFeature
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Optional
import java.util.UUID
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * MCTraveler's chat voice (spec stories 9-11, 15): the Portal's presence lines in place
 * of vanilla's, the green-name chat format on signed vanilla chat, and the emote commands.
 */
object ChatFeature {

    /**
     * The Portal's chat shape — `<green name> <message>`, no angle brackets — as a
     * registered chat type (`data/mctraveler/chat_type/chat.json`). Rebinding vanilla
     * chat to it keeps the message signed (deviation 6: chat reporting works again);
     * only the client-side rendering changes.
     */
    val CHAT_TYPE: ResourceKey<ChatType> =
        ResourceKey.create(Registries.CHAT_TYPE, Identifier.fromNamespaceAndPath("mctraveler", "chat"))

    /** The vanilla presence broadcasts we replace with the Portal's own lines. */
    private val VANILLA_PRESENCE_KEYS = setOf(
        "multiplayer.player.joined",
        "multiplayer.player.joined.renamed",
        "multiplayer.player.left",
    )

    /**
     * Players who joined this tick, announced at end of tick — once they are actually
     * in play — so a login that dies mid-placement never ghost-announces (story 5).
     */
    private data class PendingJoin(val uuid: UUID, val country: String?)

    private val pendingJoins = ArrayDeque<PendingJoin>()

    /** Players whose join line went out; only they get a leave line. */
    private val announced = mutableSetOf<UUID>()

    private const val SHRUG = "¯\\_(ツ)_/¯"
    private const val TABLEFLIP = "(╯°□°）╯︵ ┻━┻"

    fun register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(::reformatPlayerChat)
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register { _, message, _ ->
            !isVanillaPresenceMessage(message)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            ChatSelector.set(handler.player.uuid, ChatSelector.Mode.DEFAULT)
            val address = handler.getRemoteAddress() as? InetSocketAddress
            val country = address?.address
                ?.takeUnless(::isPrivateAddress)
                ?.let(GeoIpFeature::lookup)
                ?.name
            pendingJoins += PendingJoin(handler.player.uuid, country)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            ChatSelector.clear(handler.player.uuid)
            pendingJoins.removeIf { it.uuid == handler.player.uuid }
            if (announced.remove(handler.player.uuid)) {
                val line = leaveLine(handler.player.gameProfile.name)
                if (VanishFeature.isVanished(handler.player)) {
                    // Non-admins already saw this player "leave" when they
                    // vanished; only admins get the real disconnect line.
                    server.playerList.players
                        .filter { RegionsFeature.isAdmin(it) }
                        .forEach { it.sendSystemMessage(line, false) }
                } else {
                    broadcast(server, line)
                }
            }
        }
        ServerTickEvents.END_SERVER_TICK.register(::flushPendingJoins)
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerSelector(dispatcher)
            registerEmote(dispatcher, "shrug", SHRUG)
            registerEmote(dispatcher, "tableflip", TABLEFLIP)
        }
    }

    private fun registerSelector(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("chat")
                .executes { context ->
                    val player = context.source.playerOrException
                    ChatSelector.set(player.uuid, ChatSelector.Mode.DEFAULT)
                    player.sendSystemMessage(Paint.success("Chat mode: everyone"))
                    1
                }
                .then(
                    Commands.literal("server").executes { context ->
                        val player = context.source.playerOrException
                        ChatSelector.set(player.uuid, ChatSelector.Mode.SERVER)
                        player.sendSystemMessage(Paint.success("Chat mode: server only (not mirrored to Discord)"))
                        1
                    },
                )
                .then(
                    Commands.literal("region").executes { context ->
                        val player = context.source.playerOrException
                        val region = RegionTracker.regionOf(player)
                        if (region == null ||
                            (!region.isResident(player.uuid) && !RegionsFeature.isAdmin(player))
                        ) {
                            player.sendSystemMessage(Paint.error("You must stand in a region you are a member of"))
                            return@executes 1
                        }
                        ChatSelector.set(player.uuid, ChatSelector.Mode.REGION)
                        player.sendSystemMessage(Paint.success("Chat mode: region ", region.title))
                        1
                    },
                )
                .then(
                    Commands.argument("players", StringArgumentType.word())
                        .suggests { context, builder ->
                            SharedSuggestionProvider.suggest(
                                context.source.onlinePlayerNames, builder,
                            )
                        }
                        .executes { context ->
                            val sender = context.source.playerOrException
                            val raw = StringArgumentType.getString(context, "players")
                            val names = ChatSelector.parsePlayers(raw)
                            val resolved = mutableListOf<Pair<String, UUID>>()
                            for (name in names) {
                                val target = sender.level().server.playerList.getPlayerByName(name)
                                if (target == null) {
                                    sender.sendSystemMessage(Paint.gray("Player ", Paint.red(name), " not found or is offline"))
                                    return@executes 1
                                }
                                resolved += target.gameProfile.name to target.uuid
                            }
                            val recipients = resolved.filter { it.second != sender.uuid }
                            ChatSelector.set(sender.uuid, ChatSelector.Mode.PLAYERS(recipients.map { it.second }.toSet()))
                            sender.sendSystemMessage(
                                Paint.success("Chat mode: private to ", recipients.joinToString(", ") { it.first }),
                            )
                            1
                        },
                ),
        )
    }

    /**
     * `/<name>` sends [emoticon] as the player's own chat line to everyone, in the chat
     * display format (fixing the Portal's no-op — deviation 1). Commands can't produce a
     * client-signed message, so the line is server-authored (system) player chat.
     */
    private fun registerEmote(dispatcher: CommandDispatcher<CommandSourceStack>, name: String, emoticon: String) {
        dispatcher.register(
            Commands.literal(name).executes { context ->
                val player = context.source.playerOrException
                context.source.server.playerList
                    .broadcastChatMessage(PlayerChatMessage.system(emoticon), player, chatBound(player))
                1
            },
        )
    }

    /**
     * Rebinds vanilla-bound player chat to [CHAT_TYPE] with the plain green username as
     * the sender, then lets the untouched (still signed) message broadcast again. The
     * rebroadcast re-enters this handler already bound to [CHAT_TYPE] and passes straight
     * through; every other bound type (/say, /me, /msg) is left alone.
     */
    private fun reformatPlayerChat(
        message: PlayerChatMessage,
        sender: ServerPlayer,
        bound: ChatType.Bound,
    ): Boolean {
        if (bound.chatType().unwrapKey().orElse(null) != ChatType.CHAT) return true
        when (val mode = ChatSelector.modeOf(sender.uuid)) {
            ChatSelector.Mode.DEFAULT, ChatSelector.Mode.SERVER -> {
                sender.level().server.playerList.broadcastChatMessage(message, sender, chatBound(sender))
            }
            is ChatSelector.Mode.PLAYERS -> {
                val recipients = mode.recipients.mapNotNull(sender.level().server.playerList::getPlayer)
                if (recipients.isEmpty()) {
                    sender.sendSystemMessage(Paint.error("None of your chat recipients are online"))
                    return false
                }
                val names = recipients.joinToString(", ") { it.gameProfile.name }
                val line = Paint(
                    Paint.green(sender.gameProfile.name), " ", Paint.gray("→"), " ",
                    Paint.green(names), ": ", message.decoratedContent(),
                )
                (recipients + sender).distinctBy { it.uuid }.forEach { it.sendSystemMessage(line) }
            }
            ChatSelector.Mode.REGION -> {
                val region = RegionTracker.regionOf(sender)
                if (region == null ||
                    (!region.isResident(sender.uuid) && !RegionsFeature.isAdmin(sender))
                ) {
                    sender.sendSystemMessage(
                        Paint.error("You are not in a region you are a member of — message not sent"),
                    )
                    return false
                }
                val recipients = sender.level().server.playerList.players.filter {
                    region.isResident(it.uuid)
                }
                val line = Paint(
                    Paint.darkGray("["), Paint.yellow(region.title), Paint.darkGray("]"), " ",
                    Paint.green(sender.gameProfile.name), ": ", message.decoratedContent(),
                )
                (recipients + sender).distinctBy { it.uuid }.forEach { it.sendSystemMessage(line) }
            }
        }
        return false
    }

    /** [CHAT_TYPE] bound with [player]'s username, in their rank's color, as the sender. */
    private fun chatBound(player: ServerPlayer): ChatType.Bound {
        val holder = player.level().registryAccess().lookupOrThrow(Registries.CHAT_TYPE).getOrThrow(CHAT_TYPE)
        val name = eu.mctraveler.rank.RankFeature.nameColor(player)(player.gameProfile.name)
        return ChatType.Bound(holder, name, Optional.empty())
    }

    private fun isVanillaPresenceMessage(message: Component): Boolean {
        val contents = message.contents as? TranslatableContents ?: return false
        return contents.key in VANILLA_PRESENCE_KEYS
    }

    private fun flushPendingJoins(server: MinecraftServer) {
        while (true) {
            val pending = pendingJoins.removeFirstOrNull() ?: return
            // Skip anyone who dropped between login and end of tick: no ghost announcements.
            val player = server.playerList.getPlayer(pending.uuid) ?: continue
            announced += pending.uuid
            broadcast(server, joinLine(player.gameProfile.name, pending.country))
        }
    }

    private fun broadcast(server: MinecraftServer, message: Component) {
        server.playerList.broadcastSystemMessage(message, false)
    }

    /** `[+] <name> joined from <Country>` when a country is known. */
    internal fun joinLine(name: String, country: String? = null): Component = Paint.gray(
        Paint.darkGray("["),
        Paint.green("+"),
        Paint.darkGray("]"),
        " ",
        Paint.green(name),
        " joined",
        country?.let { Paint.gray(" from ", Paint.green(it)) },
    )

    /** `[-] <name> left.` — the Portal's exact leave line (note the trailing period). */
    internal fun leaveLine(name: String): Component = Paint.gray(
        Paint.darkGray("["), Paint.red("-"), Paint.darkGray("]"), " ", Paint.red(name), " left.",
    )

    /**
     * The join line [player] would produce if they were connecting right now,
     * country and all — what `/vanish` sends to non-admins to fake a
     * reconnection.
     */
    internal fun joinLineFor(player: ServerPlayer): Component {
        val country = (player.connection.getRemoteAddress() as? InetSocketAddress)?.address
            ?.takeUnless(::isPrivateAddress)
            ?.let(GeoIpFeature::lookup)
            ?.name
        return joinLine(player.gameProfile.name, country)
    }

    private fun isPrivateAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress ||
            address.isSiteLocalAddress ||
            address.isLinkLocalAddress ||
            address.isAnyLocalAddress
}
