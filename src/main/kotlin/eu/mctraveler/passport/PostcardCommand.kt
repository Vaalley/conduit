package eu.mctraveler.passport

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import eu.mctraveler.MCTraveler
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionTracker
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import eu.mctraveler.text.Sanitize
import java.util.UUID
import kotlin.math.round
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

object PostcardCommand {
    private const val MAX_CAPTION_LENGTH = 100
    private const val COOLDOWN_MILLIS = 5L * 60 * 1000

    private val lastSentAt = HashMap<UUID, Long>()

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("postcard")
                    .executes { context -> send(context, null) }
                    .then(
                        Commands.argument("caption", StringArgumentType.greedyString())
                            .executes { context ->
                                send(context, StringArgumentType.getString(context, "caption"))
                            },
                    ),
            )
        }
    }

    fun clear() {
        lastSentAt.clear()
    }

    private fun send(context: CommandContext<CommandSourceStack>, rawCaption: String?): Int {
        val player = context.source.playerOrException
        val now = System.currentTimeMillis()
        val remaining = COOLDOWN_MILLIS - (now - (lastSentAt[player.uuid] ?: 0L))
        if (remaining > 0L) {
            val seconds = (remaining + 999L) / 1000L
            context.source.sendFailure(
                Paint.error(
                    "You can send another postcard in ",
                    Paint.red("${seconds / 60}m ${seconds % 60}s"),
                ),
            )
            return 0
        }

        val persistence = MCTraveler.persistence ?: return 0
        val passport = persistence.passports.getOrCreate(
            player.uuid,
            persistence.players.firstJoin(player.uuid) ?: now,
        )
        val level = player.level()
        val biome = PassportFeature.biomeOf(player) ?: "minecraft:plains"
        val region = RegionTracker.regionOf(player)
        val regionService = RegionsFeature.requireService()
        val event = PostcardEvent(
            at = now,
            player = player.gameProfile.name,
            dimension = PassportFeature.dimensionOf(player),
            biome = biome,
            region = region?.let { regionRef(it, regionService, persistence.names::usernameFor) },
            x = round(player.x / 10.0).toInt() * 10,
            y = round(player.y / 10.0).toInt() * 10,
            z = round(player.z / 10.0).toInt() * 10,
            dayTime = level.getOverworldClockTime() % 24000,
            raining = level.isRaining,
            thundering = level.isThundering,
            caption = rawCaption
                ?.let { Sanitize.line(it, MAX_CAPTION_LENGTH) }
                ?.takeIf(String::isNotBlank),
        )
        passport.postcards++
        persistence.passports.markDirty(player.uuid)
        PassportEvents.record(event)
        PassportFeature.unlockStamps(player, passport)
        lastSentAt[player.uuid] = now
        context.source.sendSuccess(
            { Paint.success("Postcard sent from ", Paint.green(prettyBiomeName(biome)), "!") },
            false,
        )
        return Command.SINGLE_SUCCESS
    }

    private fun regionRef(
        region: Region,
        service: eu.mctraveler.region.RegionService,
        nameFor: (UUID) -> String?,
    ): RegionRef =
        RegionRef(
            id = service.stableIdOf(region),
            title = region.title,
            embassy = Region.EMBASSY_FLAG in region.flags,
            owner = if (Region.EMBASSY_FLAG in region.flags) {
                region.members.firstOrNull()?.let(nameFor)
            } else {
                null
            },
        )

    private fun prettyBiomeName(id: String): String =
        id.substringAfter(':').replace('_', ' ').split(' ')
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
}
