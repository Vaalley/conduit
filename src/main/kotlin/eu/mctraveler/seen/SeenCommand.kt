package eu.mctraveler.seen

import com.google.common.net.InetAddresses
import com.mojang.brigadier.arguments.StringArgumentType
import eu.mctraveler.MCTraveler
import eu.mctraveler.chat.ChatFeature
import eu.mctraveler.economy.Economy
import eu.mctraveler.geo.GeoIpFeature
import eu.mctraveler.moderation.ModerationFeature
import eu.mctraveler.text.Paint
import eu.mctraveler.vanish.VanishFeature
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

/**
 * `/seen <player>`: anyone can see when a player was last on, when they first
 * joined, which country they last joined from and what they own. A vanished
 * admin reads as offline to everyone who is not an admin; the detailed,
 * admin-only view of a player is `/whois`.
 */
object SeenCommand {

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("seen").then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests(ModerationFeature.playerSuggestions)
                        .executes { context -> seen(context.source, StringArgumentType.getString(context, "player")) },
                ),
            )
        }
    }

    private fun seen(source: CommandSourceStack, name: String): Int {
        val persistence = MCTraveler.persistence ?: return 0
        val online = source.server.playerList.getPlayerByName(name)
        val uuid = online?.uuid ?: persistence.names.uuidFor(name)
        if (uuid == null) {
            source.sendFailure(Paint.error("Unknown player ", Paint.red(name)))
            return 0
        }
        val displayName = online?.gameProfile?.name ?: persistence.names.usernameFor(uuid) ?: name
        val players = persistence.players
        val viewer = source.player
        val visibleOnline = online != null && (viewer == null || !VanishFeature.isHiddenFrom(online, viewer))
        val lastOff = players.lastLogoutAt(uuid) ?: players.lastLoginAt(uuid)
        if (!visibleOnline && lastOff == null) {
            source.sendFailure(Paint.error("Player ", Paint.red(displayName), " has never joined"))
            return 0
        }

        val headline = if (visibleOnline) {
            Paint.yellow(Paint.green(displayName), " is online right now.")
        } else {
            Paint.yellow(
                Paint.green(displayName),
                " was last seen ${SeenFormat.elapsed(System.currentTimeMillis() - lastOff!!)} ago.",
            )
        }
        val firstJoin = players.firstJoin(uuid) ?: persistence.passports.get(uuid)?.firstJoin
        source.sendSystemMessage(headline)
        source.sendSystemMessage(line("First joined on", firstJoin?.let(SeenFormat::date) ?: "Unknown"))
        source.sendSystemMessage(line("Last joined from", countryOf(players.lastIp(uuid)) ?: "Unknown"))
        source.sendSystemMessage(line("Balance", Economy.format(persistence.economy.balanceOf(uuid))))
        return 1
    }

    private fun line(label: String, value: String) = Paint.yellow("$label ", Paint.white(value))

    private fun countryOf(ip: String?): String? {
        if (ip == null) return null
        val address = try {
            InetAddresses.forString(ip)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (ChatFeature.isPrivateAddress(address)) return null
        return GeoIpFeature.lookup(address)?.name
    }
}
