package eu.mctraveler.moderation

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import eu.mctraveler.MCTraveler
import eu.mctraveler.command.CommandTree
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.text.Paint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import java.net.InetSocketAddress
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.server.players.NameAndId
import net.minecraft.server.level.ServerPlayer

object ModerationFeature {
    val playerSuggestions: SuggestionProvider<CommandSourceStack> = SuggestionProvider { context, builder ->
        val online = context.source.server.playerList.players.map { it.gameProfile.name }
        val known = MCTraveler.persistence?.names?.knownUsernames().orEmpty()
        SharedSuggestionProvider.suggest((online + known).distinct(), builder)
    }

    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
    private data class PendingLogin(val uuid: java.util.UUID, val at: Long, val ip: String?)
    private val pendingLogins = ArrayDeque<PendingLogin>()

    fun register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { _, sender, _ ->
            allowMuted(sender)
        }
        ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register { _, source, _ ->
            source.player?.let(::allowMuted) ?: true
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            ActionRecorder.clear(handler.player.uuid)
            try {
                MCTraveler.persistence?.players?.setLogoutMetadata(
                    handler.player.uuid,
                    System.currentTimeMillis(),
                    eu.mctraveler.region.RegionWorlds.legacyName(handler.player.level().dimension()),
                    handler.player.x,
                    handler.player.y,
                    handler.player.z,
                )
            } catch (failure: Exception) {
                MCTraveler.LOGGER.error("Failed to save logout metadata for ${handler.player.gameProfile.name}", failure)
            }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val ip = (handler.getRemoteAddress() as? InetSocketAddress)?.address?.hostAddress
            pendingLogins += PendingLogin(handler.player.uuid, System.currentTimeMillis(), ip)
        }
        ServerTickEvents.END_SERVER_TICK.register {
            val players = MCTraveler.persistence?.players ?: return@register
            while (true) {
                val pending = pendingLogins.removeFirstOrNull() ?: break
                try {
                    players.setLoginMetadata(pending.uuid, pending.at, pending.ip)
                } catch (failure: Exception) {
                    MCTraveler.LOGGER.error("Failed to save login metadata for ${pending.uuid}", failure)
                }
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            MCTraveler.persistence?.actions?.flush()
        }
        ActionRecorder.register()
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            CommandTree.removeRootCommands(dispatcher, "ban", "banlist", "kick", "pardon")
            registerCommands(dispatcher)
        }
    }

    fun isMuted(player: ServerPlayer): Boolean = MCTraveler.persistence?.punishments?.activeMute(player.uuid) != null

    fun authorized(source: CommandSourceStack): Boolean {
        val player = source.player ?: return true
        if (RegionsFeature.isAdmin(player)) return true
        source.sendFailure(Paint.error("You must be an admin to use this command"))
        return false
    }

    fun allowMuted(player: ServerPlayer): Boolean {
        val mute = MCTraveler.persistence?.punishments?.activeMute(player.uuid) ?: return true
        player.sendSystemMessage(
            Paint.error("You are muted: ", mute.reason, " (", remaining(mute.expiresAt), ")"),
        )
        return false
    }

    fun loginDenial(nameAndId: NameAndId): Component? {
        val punishment = MCTraveler.persistence?.punishments?.activeBan(nameAndId.id()) ?: return null
        return Paint(
            "You are banned from this server\n",
            Paint.gray("Reason: "), punishment.reason,
            "\n", Paint.gray(expiryLine(punishment.expiresAt)),
        )
    }

    private fun registerCommands(dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>) {
        val gate: (CommandSourceStack) -> Boolean = { source ->
            source.player?.let(RegionsFeature::isAdmin) ?: true
        }
        dispatcher.register(
            Commands.literal("ban").requires(gate)
                .then(target("player").then(tail("tail").executes { context -> ban(context.source, target(context), tail(context)) }))
                .then(target("player").executes { context -> ban(context.source, target(context), null) }),
        )
        dispatcher.register(
            Commands.literal("unban").requires(gate)
                .then(target("player").executes { context -> lift(context.source, target(context), PunishmentType.BAN) }),
        )
        dispatcher.register(
            Commands.literal("pardon").requires(gate)
                .then(target("player").executes { context -> lift(context.source, target(context), PunishmentType.BAN) }),
        )
        dispatcher.register(
            Commands.literal("banlist").requires(gate)
                .executes { context -> banList(context.source, 1) }
                .then(
                    Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes { context -> banList(context.source, IntegerArgumentType.getInteger(context, "page")) },
                ),
        )
        dispatcher.register(
            Commands.literal("mute").requires(gate)
                .then(target("player").then(tail("tail").executes { context -> mute(context.source, target(context), tail(context)) }))
                .then(target("player").executes { context -> mute(context.source, target(context), null) }),
        )
        dispatcher.register(
            Commands.literal("unmute").requires(gate)
                .then(target("player").executes { context -> lift(context.source, target(context), PunishmentType.MUTE) }),
        )
        dispatcher.register(
            Commands.literal("kick").requires(gate)
                .then(target("player").then(tail("tail").executes { context -> kick(context.source, target(context), tail(context)) }))
                .then(target("player").executes { context -> kick(context.source, target(context), null) }),
        )
        dispatcher.register(
            Commands.literal("warn").requires(gate)
                .then(
                    target("player").then(
                        Commands.argument("reason", StringArgumentType.greedyString())
                            .executes { context -> warn(context.source, target(context), StringArgumentType.getString(context, "reason")) },
                    ),
                ),
        )
        dispatcher.register(
            Commands.literal("warnings").requires(gate)
                .then(target("player").executes { context -> warnings(context.source, target(context)) }),
        )
        dispatcher.register(
            Commands.literal("unwarn").requires(gate)
                .then(
                    Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes { context -> unwarn(context.source, IntegerArgumentType.getInteger(context, "id")) },
                ),
        )
        dispatcher.register(
            Commands.literal("history").requires(gate)
                .then(target("player").executes { context -> history(context.source, target(context)) }),
        )
        dispatcher.register(
            Commands.literal("note").requires(gate)
                .then(
                    target("player").then(
                        Commands.argument("text", StringArgumentType.greedyString())
                            .executes { context -> note(context.source, target(context), StringArgumentType.getString(context, "text")) },
                    ),
                ),
        )
        WhoisCommand.register(dispatcher, gate)
        ActionRecorder.registerCommands(dispatcher, gate)
        InvseeMenu.registerCommand(dispatcher, gate)
    }

    private fun target(name: String) =
        Commands.argument(name, StringArgumentType.word()).suggests(playerSuggestions)

    private fun tail(name: String) =
        Commands.argument(name, StringArgumentType.greedyString())

    private fun target(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): String =
        StringArgumentType.getString(context, "player")

    private fun tail(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): String =
        StringArgumentType.getString(context, "tail")

    private fun resolve(source: CommandSourceStack, name: String): Pair<java.util.UUID, String>? {
        val persistence = MCTraveler.persistence ?: return null
        val online = source.server.playerList.getPlayerByName(name)
        val uuid = online?.uuid ?: persistence.names.uuidFor(name)
        if (uuid == null) {
            source.sendFailure(Paint.error("Unknown player ", Paint.red(name)))
            return null
        }
        return uuid to (online?.gameProfile?.name ?: persistence.names.usernameFor(uuid) ?: name)
    }

    private fun staff(source: CommandSourceStack): Pair<java.util.UUID?, String> =
        source.player?.let { it.uuid to it.gameProfile.name } ?: (null to "Console")

    private fun ban(source: CommandSourceStack, name: String, rawTail: String?): Int {
        if (!authorized(source)) return 0
        val resolved = resolve(source, name) ?: return 0
        val parsed = parseTail(rawTail)
        val (staff, staffName) = staff(source)
        val punishment = store().add(
            PunishmentType.BAN, resolved.first, resolved.second, staff, staffName, parsed.reason,
            parsed.duration?.let { System.currentTimeMillis() + it },
        )
        source.server.playerList.getPlayer(resolved.first)?.connection?.disconnect(
            checkNotNull(loginDenial(NameAndId(resolved.first, resolved.second))),
        )
        source.sendSystemMessage(
            Paint.success("Banned ", Paint.green(resolved.second), " ", Paint.gray("(${durationText(parsed.duration)})"), ": ", parsed.reason),
        )
        return punishment.id
    }

    private fun mute(source: CommandSourceStack, name: String, rawTail: String?): Int {
        if (!authorized(source)) return 0
        val resolved = resolve(source, name) ?: return 0
        val parsed = parseTail(rawTail)
        val (staff, staffName) = staff(source)
        store().add(
            PunishmentType.MUTE, resolved.first, resolved.second, staff, staffName, parsed.reason,
            parsed.duration?.let { System.currentTimeMillis() + it },
        )
        source.sendSystemMessage(
            Paint.success("Muted ", Paint.green(resolved.second), " ", Paint.gray("(${durationText(parsed.duration)})"), ": ", parsed.reason),
        )
        return 1
    }

    private fun lift(source: CommandSourceStack, name: String, type: PunishmentType): Int {
        if (!authorized(source)) return 0
        val resolved = resolve(source, name) ?: return 0
        val lifted = store().lift(type, resolved.first, staff(source).second)
            ?: run {
                source.sendFailure(Paint.error("No active ${type.name.lowercase()} for ", Paint.red(resolved.second)))
                return 0
            }
        source.sendSystemMessage(Paint.success("Lifted ", type.name, " for ", Paint.green(lifted.targetName)))
        return 1
    }

    private fun kick(source: CommandSourceStack, name: String, rawReason: String?): Int {
        if (!authorized(source)) return 0
        val resolved = resolve(source, name) ?: return 0
        val reason = rawReason?.trim()?.takeIf(String::isNotEmpty) ?: "No reason given"
        val target = source.server.playerList.getPlayer(resolved.first)
            ?: run {
                source.sendFailure(Paint.error("Player ", Paint.red(resolved.second), " is offline"))
                return 0
            }
        val (staff, staffName) = staff(source)
        store().add(PunishmentType.KICK, resolved.first, resolved.second, staff, staffName, reason, null, active = false)
        target.connection.disconnect(Paint("Kicked: ", reason))
        source.sendSystemMessage(Paint.success("Kicked ", Paint.green(resolved.second), ": ", reason))
        return 1
    }

    private fun warn(source: CommandSourceStack, name: String, reason: String): Int {
        if (!authorized(source)) return 0
        val resolved = resolve(source, name) ?: return 0
        val text = reason.trim().ifEmpty { "No reason given" }
        val (staff, staffName) = staff(source)
        store().add(PunishmentType.WARN, resolved.first, resolved.second, staff, staffName, text, null)
        source.server.playerList.getPlayer(resolved.first)?.sendSystemMessage(Paint.error("You have been warned: ", text))
        source.sendSystemMessage(Paint.success("Warned ", Paint.green(resolved.second), ": ", text))
        return 1
    }

    private fun warnings(source: CommandSourceStack, name: String): Int {
        if (!authorized(source)) return 0
        val resolved = resolve(source, name) ?: return 0
        val warnings = store().history(resolved.first).filter { it.type == PunishmentType.WARN && it.active }
        if (warnings.isEmpty()) {
            source.sendSystemMessage(Paint.gray("No active warnings for ${resolved.second}"))
            return 1
        }
        warnings.forEach {
            source.sendSystemMessage(Paint.gray("#${it.id} ${dateFormat.format(Instant.ofEpochMilli(it.createdAt))} by ${it.staffName}: ${it.reason}"))
        }
        return 1
    }

    private fun unwarn(source: CommandSourceStack, id: Int): Int {
        if (!authorized(source)) return 0
        val punishment = store().byId(id)
        if (punishment == null || punishment.type != PunishmentType.WARN || !punishment.active) {
            source.sendFailure(Paint.error("No active warning with id ", Paint.red(id)))
            return 0
        }
        store().deactivate(id, staff(source).second)
        source.sendSystemMessage(Paint.success("Warning ", id, " lifted"))
        return 1
    }

    private fun history(source: CommandSourceStack, name: String): Int {
        if (!authorized(source)) return 0
        val resolved = resolve(source, name) ?: return 0
        val history = store().history(resolved.first)
        if (history.isEmpty()) {
            source.sendSystemMessage(Paint.gray("No history for ${resolved.second}"))
            return 1
        }
        history.forEach { punishment ->
            source.sendSystemMessage(
                Paint.gray("#${punishment.id} ${punishment.type.name} ${dateFormat.format(Instant.ofEpochMilli(punishment.createdAt))} by ${punishment.staffName}: ${punishment.reason} [${status(punishment)}]"),
            )
        }
        return 1
    }

    private fun note(source: CommandSourceStack, name: String, text: String): Int {
        if (!authorized(source)) return 0
        val resolved = resolve(source, name) ?: return 0
        val (staff, staffName) = staff(source)
        store().add(PunishmentType.NOTE, resolved.first, resolved.second, staff, staffName, text, null)
        source.sendSystemMessage(Paint.success("Added note for ", Paint.green(resolved.second)))
        return 1
    }

    private fun banList(source: CommandSourceStack, page: Int): Int {
        if (!authorized(source)) return 0
        val bans = store().activeBans()
        val pageEntries = bans.drop((page - 1) * 10).take(10)
        if (pageEntries.isEmpty()) {
            source.sendSystemMessage(Paint.gray("No active bans"))
            return 1
        }
        pageEntries.forEach {
            source.sendSystemMessage(
                Paint.gray("#${it.id} ${it.targetName} — ${it.reason} (by ${it.staffName}, ${expiryLine(it.expiresAt)})"),
            )
        }
        return 1
    }

    private fun parseTail(rawTail: String?): ParsedTail {
        val tail = rawTail?.trim().orEmpty()
        if (tail.isEmpty()) return ParsedTail(null, "No reason given")
        val first = tail.substringBefore(' ')
        val duration = runCatching { Durations.parse(first) }.getOrNull()
        return if (duration != null || first.equals("perm", true) || first.equals("permanent", true)) {
            val reason = tail.substringAfter(' ', "").trim().ifEmpty { "No reason given" }
            ParsedTail(duration, reason)
        } else {
            ParsedTail(null, tail)
        }
    }

    private fun store() = checkNotNull(MCTraveler.persistence).punishments

    private data class ParsedTail(val duration: Long?, val reason: String)

    private fun durationText(duration: Long?): String =
        duration?.let { "for ${formatDuration(it)}" } ?: "permanently"

    private fun remaining(expiresAt: Long?): String =
        expiresAt?.let { formatDuration((it - System.currentTimeMillis()).coerceAtLeast(0)) } ?: "permanent"

    private fun expiryLine(expiresAt: Long?): String =
        expiresAt?.let { "Expires: ${dateFormat.format(Instant.ofEpochMilli(it))}" } ?: "Permanent"

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1_000
        return when {
            seconds % 604_800 == 0L -> "${seconds / 604_800}w"
            seconds % 86_400 == 0L -> "${seconds / 86_400}d"
            seconds % 3_600 == 0L -> "${seconds / 3_600}h"
            seconds % 60 == 0L -> "${seconds / 60}m"
            else -> "${seconds}s"
        }
    }

    private fun status(punishment: Punishment): String =
        when {
            punishment.active -> "active"
            punishment.liftedBy != null -> "lifted by ${punishment.liftedBy}"
            else -> "expired"
        }
}
