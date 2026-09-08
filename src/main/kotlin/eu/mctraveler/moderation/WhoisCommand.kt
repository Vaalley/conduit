package eu.mctraveler.moderation

import com.mojang.brigadier.arguments.StringArgumentType
import eu.mctraveler.MCTraveler
import eu.mctraveler.crystal.CrystalEnergy
import eu.mctraveler.passport.Passport
import eu.mctraveler.rank.RankFeature
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import eu.mctraveler.vanish.VanishFeature
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.level.ServerPlayer

object WhoisCommand {
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

    fun register(dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>, gate: (CommandSourceStack) -> Boolean) {
        dispatcher.register(
            Commands.literal("whois").requires(gate)
                .then(Commands.argument("player", StringArgumentType.word()).executes { context ->
                    show(context.source, StringArgumentType.getString(context, "player"))
                }),
        )
        dispatcher.register(
            Commands.literal("seen").requires(gate)
                .then(Commands.argument("player", StringArgumentType.word()).executes { context ->
                    seen(context.source, StringArgumentType.getString(context, "player"))
                }),
        )
    }

    private fun show(source: CommandSourceStack, name: String): Int {
        if (!ModerationFeature.authorized(source)) return 0
        val resolved = resolve(source, name) ?: return 0
        val (uuid, displayName) = resolved
        val online = source.server.playerList.getPlayer(uuid)
        val players = MCTraveler.persistence?.players ?: return 0
        source.sendSuccess({ Paint.aqua.bold("Whois: $displayName") }, false)
        line(source, "Name", displayName)
        line(source, "UUID", uuid)
        line(source, "Rank", online?.let { RankFeature.rankOf(it) } ?: players.rank(uuid) ?: "N/A")
        line(source, "Online", online != null)
        if (online != null) {
            line(source, "World", RegionWorlds.legacyName(online.level().dimension()))
            line(source, "Position", blockPosition(online.blockPosition()))
            line(source, "Vanished", VanishFeature.isVanished(online))
        } else {
            line(source, "Last seen", players.lastLogoutAt(uuid)?.let(::relative) ?: "N/A")
            line(source, "World", players.lastLocationWorld(uuid) ?: "N/A")
            line(source, "Position", position(players.lastX(uuid), players.lastY(uuid), players.lastZ(uuid)))
        }
        line(source, "First join", MCTraveler.persistence?.passports?.get(uuid)?.firstJoin?.let(::date) ?: "N/A")
        line(source, "IP", players.lastIp(uuid) ?: "N/A")
        line(source, "Crystal energy", CrystalEnergy.energyOf(players, uuid))
        val regions = residentRegions(uuid)
        line(source, "Regions", regions.joinToString(", ").ifEmpty { "None" })
        val passport = MCTraveler.persistence?.passports?.get(uuid)
        if (passport != null) {
            line(source, "Passport", "${passport.biomes.size} biomes, ${passport.dimensions.size} dimensions, ${passport.regions.size} regions, ${passport.deaths} deaths, ${passport.stamps.size} stamps")
        }
        val history = MCTraveler.persistence?.punishments?.history(uuid).orEmpty()
        line(source, "Active punishments", "${history.count { it.type == PunishmentType.BAN && it.active }} bans, ${history.count { it.type == PunishmentType.MUTE && it.active }} mutes, ${history.count { it.type == PunishmentType.WARN && it.active }} warns, ${history.count { it.type == PunishmentType.NOTE && it.active }} notes")
        return 1
    }

    private fun seen(source: CommandSourceStack, name: String): Int {
        if (!ModerationFeature.authorized(source)) return 0
        val resolved = resolve(source, name) ?: return 0
        val (uuid, displayName) = resolved
        val online = source.server.playerList.getPlayer(uuid)
        if (online != null) {
            source.sendSuccess(
                { Paint("$displayName is online for ${relative(MCTraveler.persistence?.players?.lastLoginAt(uuid))} in ${RegionWorlds.legacyName(online.level().dimension())} at ${blockPosition(online.blockPosition())}") },
                false,
            )
            return 1
        }
        val players = MCTraveler.persistence?.players ?: return 0
        val logout = players.lastLogoutAt(uuid)
        if (logout == null) {
            source.sendFailure(Paint.error("Player ", Paint.red(displayName), " has never joined"))
            return 0
        }
        source.sendSuccess(
            { Paint("$displayName was last seen ${relative(logout)} (${dateTime(logout)}) in ${players.lastLocationWorld(uuid) ?: "unknown"} at ${position(players.lastX(uuid), players.lastY(uuid), players.lastZ(uuid))}") },
            false,
        )
        return 1
    }

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

    private fun residentRegions(uuid: java.util.UUID): List<String> {
        val result = mutableListOf<String>()
        fun walk(regions: List<Region>) {
            regions.forEach {
                if (it.isResident(uuid)) result += it.title
                walk(it.subRegions)
            }
        }
        walk(RegionsFeature.requireService().roots)
        return result
    }

    private fun line(source: CommandSourceStack, label: String, value: Any?) {
        source.sendSuccess({ Paint(Paint.gray("$label: "), value ?: "N/A") }, false)
    }

    private fun position(x: Double?, y: Double?, z: Double?): String =
        if (x == null || y == null || z == null) "N/A" else "${x.toInt()} ${y.toInt()} ${z.toInt()}"

    private fun blockPosition(position: net.minecraft.core.BlockPos): String =
        "${position.x} ${position.y} ${position.z}"

    private fun date(value: Long): String = dateFormat.format(Instant.ofEpochMilli(value))
    private fun dateTime(value: Long): String = Instant.ofEpochMilli(value).toString()

    private fun relative(value: Long?): String {
        val millis = (System.currentTimeMillis() - (value ?: System.currentTimeMillis())).coerceAtLeast(0)
        val seconds = millis / 1_000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3_600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3_600}h"
            else -> "${seconds / 86_400}d"
        }
    }
}
