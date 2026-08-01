package eu.mctraveler.embassy

import com.google.gson.JsonObject
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionTracker
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * `/embassy` — the plot lifecycle (spec stories 8, 9, 11, 16, 17, 18), ported
 * from Nucleus's `EmbassyCommand`.
 *
 * Both subcommands are admin-only, gated in the body rather than by a Brigadier
 * requirement so the tree stays visible and a malformed invocation still gets
 * its answer (the house rule). `delete` is deliberately two-step: it asks for
 * the embassy's exact title back, and offers a clickable line that types it.
 */
object EmbassyCommands {

    /** The one flag that makes a region an embassy. */
    private const val EMBASSY = "EMBASSY"

    /** The metadata key an embassy's anchor reads its destination from. */
    const val DESTINATION = "embassy-destination"

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("embassy")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    // Nucleus sent two plain lines, with no prefix at all.
                    player.sendSystemMessage(Paint("/embassy create"))
                    player.sendSystemMessage(Paint("/embassy delete"))
                    Command.SINGLE_SUCCESS
                }
                .then(Commands.literal("create").executes { ctx -> reply(ctx, ::create) })
                .then(
                    Commands.literal("delete")
                        .executes { ctx -> reply(ctx) { delete(it, "") } }
                        .then(
                            Commands.argument("title", StringArgumentType.greedyString())
                                .executes { ctx ->
                                    reply(ctx) { delete(it, StringArgumentType.getString(ctx, "title")) }
                                },
                        ),
                ),
        )
    }

    /** Runs a handler for the sending player and sends them its one reply. */
    private inline fun reply(
        ctx: CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> Component,
    ): Int {
        val player = ctx.source.playerOrException
        player.sendSystemMessage(handler(player))
        return Command.SINGLE_SUCCESS
    }

    /**
     * Allocates the next plot on the spiral, builds it, registers the region
     * over its lawn and drops the sender in the middle of it.
     *
     * The destination the plot's anchor will send visitors to is where the
     * sender is standing *now* — read before the teleport, which is the whole
     * reason it is captured here rather than on arrival. Nothing records the
     * origin: entering the dimension does that by itself (ticket 01).
     */
    private fun create(player: ServerPlayer): Component {
        RegionsFeature.adminGate(player)?.let { return it }
        if (EmbassiesFeature.isEmbassies(player.level())) {
            return Paint.error("You must not be in the embassies world")
        }
        val server = player.level().server
        val level = checkNotNull(server.getLevel(EmbassiesFeature.DIMENSION)) {
            "the embassies dimension is not loaded"
        }

        val plot = EmbassyPlots.nextFreePlot()
        EmbassyPlots.populate(level, plot)

        val region = Region(
            title = "Unnamed Embassy",
            world = RegionWorlds.EMBASSIES,
            startX = plot.x * 16 + EmbassyPlots.GRASS_MIN,
            startZ = plot.z * 16 + EmbassyPlots.GRASS_MIN,
            endX = plot.x * 16 + EmbassyPlots.GRASS_MAX,
            endZ = plot.z * 16 + EmbassyPlots.GRASS_MAX,
            // Y bounds left at the full-height defaults, which is what Nucleus
            // wrote and what the store omits.
        )
        region.members.add(player.uuid)
        region.flags.add(EMBASSY)
        region.metadata[DESTINATION] = destinationOf(player)
        RegionsFeature.requireService().add(region, parent = null)

        player.teleportTo(
            level,
            plot.x * 16 + EmbassyPlots.ANCHOR_LOCAL + 0.5,
            1.0,
            plot.z * 16 + EmbassyPlots.ANCHOR_LOCAL + 0.5,
            emptySet(),
            player.yRot,
            player.xRot,
            false,
        )
        return Paint.success("Created embassy")
    }

    /** Where [player] is standing, in the shape an anchor reads back. */
    private fun destinationOf(player: ServerPlayer): JsonObject = JsonObject().apply {
        addProperty("x", player.x)
        addProperty("y", player.y)
        addProperty("z", player.z)
        addProperty("yaw", player.yRot)
        addProperty("pitch", player.xRot)
        // The legacy world string, so the file reads like Nucleus's did.
        addProperty("world", RegionWorlds.legacyName(player.level().dimension()))
    }

    /**
     * Takes an embassy down, once the sender has typed its title back.
     *
     * The guards are Nucleus's, in its order, and the middle one tests the
     * EMBASSY flag rather than "is there a region here": the void between plots
     * is a region too (the synthetic world one), so a player standing in it
     * must be told they are not in an embassy.
     */
    private fun delete(player: ServerPlayer, title: String): Component {
        RegionsFeature.adminGate(player)?.let { return it }
        val level = player.level()
        if (!EmbassiesFeature.isEmbassies(level)) {
            return Paint.error("You must be in the embassies world")
        }
        val region = RegionTracker.regionOf(player)
        if (region == null || EMBASSY !in region.flags) {
            return Paint.error("You must be in an embassy")
        }
        if (!region.isResident(player.uuid)) {
            return Paint.error("You are not a member of this embassy")
        }
        if (title != region.title) {
            return Paint.warning(
                "Are you sure you want to delete this embassy? The embassy build will also be " +
                    "deleted. Click ",
                Paint.gold.runs("/embassy delete ${region.title}")("here"),
                " to confirm. This cannot be undone.",
            )
        }

        EmbassyPlots.clear(level, EmbassyPlots.plotOf(player.blockX, player.blockZ))
        // Everyone standing in it loses its sidebar before it stops existing.
        RegionTracker.clear(level.server, region)
        RegionsFeature.requireService().remove(region)
        return Paint.success("Embassy deleted")
    }
}
