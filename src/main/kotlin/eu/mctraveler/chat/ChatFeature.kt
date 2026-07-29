package eu.mctraveler.chat

import com.mojang.brigadier.CommandDispatcher
import eu.mctraveler.text.Paint
import java.util.Optional
import java.util.UUID
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
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
    private val pendingJoins = ArrayDeque<UUID>()

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
            pendingJoins += handler.player.uuid
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            pendingJoins.remove(handler.player.uuid)
            if (announced.remove(handler.player.uuid)) {
                broadcast(server, leaveLine(handler.player.gameProfile.name))
            }
        }
        ServerTickEvents.END_SERVER_TICK.register(::flushPendingJoins)
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerEmote(dispatcher, "shrug", SHRUG)
            registerEmote(dispatcher, "tableflip", TABLEFLIP)
        }
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
        sender.level().server.playerList.broadcastChatMessage(message, sender, chatBound(sender))
        return false
    }

    /** [CHAT_TYPE] bound with [player]'s plain username in green as the sender. */
    private fun chatBound(player: ServerPlayer): ChatType.Bound {
        val holder = player.level().registryAccess().lookupOrThrow(Registries.CHAT_TYPE).getOrThrow(CHAT_TYPE)
        return ChatType.Bound(holder, Paint.green(player.gameProfile.name), Optional.empty())
    }

    private fun isVanillaPresenceMessage(message: Component): Boolean {
        val contents = message.contents as? TranslatableContents ?: return false
        return contents.key in VANILLA_PRESENCE_KEYS
    }

    private fun flushPendingJoins(server: MinecraftServer) {
        while (true) {
            val uuid = pendingJoins.removeFirstOrNull() ?: return
            // Skip anyone who dropped between login and end of tick: no ghost announcements.
            val player = server.playerList.getPlayer(uuid) ?: continue
            announced += uuid
            broadcast(server, joinLine(player.gameProfile.name))
        }
    }

    private fun broadcast(server: MinecraftServer, message: Component) {
        server.playerList.broadcastSystemMessage(message, false)
    }

    /** `[+] <name> joined` — the Portal's exact join line. */
    private fun joinLine(name: String): Component = Paint.gray(
        Paint.darkGray("["), Paint.green("+"), Paint.darkGray("]"), " ", Paint.green(name), " joined",
    )

    /** `[-] <name> left.` — the Portal's exact leave line (note the trailing period). */
    private fun leaveLine(name: String): Component = Paint.gray(
        Paint.darkGray("["), Paint.red("-"), Paint.darkGray("]"), " ", Paint.red(name), " left.",
    )
}
