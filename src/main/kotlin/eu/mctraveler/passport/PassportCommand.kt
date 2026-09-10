package eu.mctraveler.passport

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import eu.mctraveler.MCTraveler
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer

object PassportCommand {
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("passport")
                    .executes { context -> show(context, context.source.playerOrException.gameProfile.name) }
                    .then(
                        Commands.literal("text")
                            .executes { context -> showText(context, context.source.playerOrException.gameProfile.name) }
                            .then(
                                Commands.argument("player", StringArgumentType.word())
                                    .suggests { context, builder ->
                                        SharedSuggestionProvider.suggest(
                                            context.source.server.playerList.players.map { it.gameProfile.name },
                                            builder,
                                        )
                                    }
                                    .executes { context ->
                                        showText(context, StringArgumentType.getString(context, "player"))
                                    },
                            ),
                    )
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { context, builder ->
                                SharedSuggestionProvider.suggest(
                                    context.source.server.playerList.players.map { it.gameProfile.name },
                                    builder,
                                )
                            }
                            .executes { context -> show(context, StringArgumentType.getString(context, "player")) },
                    ),
            )
        }
    }

    private fun show(context: CommandContext<CommandSourceStack>, requested: String): Int {
        val server = context.source.server
        val persistence = MCTraveler.persistence ?: return 0
        val uuid = server.playerList.getPlayerByName(requested)?.uuid ?: persistence.names.uuidFor(requested)
        if (uuid == null) {
            context.source.sendFailure(Paint.error("Unknown player ", Paint.red(requested)))
            return 0
        }
        if (persistence.passports.get(uuid) == null) {
            context.source.sendFailure(Paint.error("No passport found for ", Paint.red(requested)))
            return 0
        }
        PassportMenu.open(context.source.playerOrException, uuid)
        return Command.SINGLE_SUCCESS
    }

    private fun showText(context: CommandContext<CommandSourceStack>, requested: String): Int {
        val server = context.source.server
        val persistence = MCTraveler.persistence ?: return 0
        val uuid = server.playerList.getPlayerByName(requested)?.uuid
            ?: persistence.names.uuidFor(requested)
        if (uuid == null) {
            context.source.sendFailure(Paint.error("Unknown player ", Paint.red(requested)))
            return 0
        }
        val passport = persistence.passports.get(uuid)
        if (passport == null) {
            context.source.sendFailure(Paint.error("No passport found for ", Paint.red(requested)))
            return 0
        }
        val name = persistence.names.usernameFor(uuid) ?: requested
        val regionService = RegionsFeature.requireService()
        val liveRegions = passport.regions.keys.count { regionService.byStableId(it) != null }
        val embassyCount = PassportJson.embassyCount(passport, regionService)
        context.source.sendSuccess(
            {
                Paint.gray(
                    "Passport of ",
                    Paint.green(name),
                    " — traveler since ",
                    Paint.green(dateFormat.format(Instant.ofEpochMilli(passport.firstJoin))),
                )
            },
            false,
        )
        context.source.sendSuccess(
            { Paint.gray("Biomes: ", Paint.green(passport.biomes.size), " · Dimensions: ", Paint.green(passport.dimensions.size),
                " · Regions: ", Paint.green(liveRegions), " (", Paint.green(embassyCount), " embassies)") },
            false,
        )
        context.source.sendSuccess(
            { Paint.gray("Walked ", Paint.green(PassportFormatting.formatDistance(passport.distance.walk)),
                " · Ridden ", Paint.green(PassportFormatting.formatDistance(passport.distance.ride)),
                " · Flown ", Paint.green(PassportFormatting.formatDistance(passport.distance.fly)),
                " · Swum ", Paint.green(PassportFormatting.formatDistance(passport.distance.swim))) },
            false,
        )
        context.source.sendSuccess(
            { Paint.gray("Deaths: ", Paint.green(passport.deaths), " · Mined: ", Paint.green(passport.blocksMined)) },
            false,
        )
        val recentStamps = passport.stamps.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { (id, _) ->
                Stamps.byId(id)?.let { "${it.icon} ${it.title}" } ?: id
            }
            .joinToString(", ")
            .ifEmpty { "none" }
        context.source.sendSuccess(
            {
                Paint.gray(
                    "Stamps: ",
                    Paint.green("${passport.stamps.size}/${Stamps.ALL.size}"),
                    " · last: 🏅 ",
                    Paint.green(recentStamps),
                )
            },
            false,
        )
        return Command.SINGLE_SUCCESS
    }
}
