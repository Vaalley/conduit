package eu.mctraveler.region

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import eu.mctraveler.text.Paint
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * The `/region` + `/rg` command family — the Portal's region lifecycle,
 * membership and admin commands, message-for-message (RegionFeature.ts; §2.8
 * of the feature inventory).
 *
 * Where the Portal read a region or position it had tracked from move packets,
 * these handlers read the player's live server-side state — same meaning,
 * never stale. Commands that change what a region's sidebar says tell
 * [RegionTracker] to redraw it for everyone standing inside. Malformed
 * invocations get USAGE messages (deviation 5).
 */
object RegionCommands {

    /** `/rg start` capture: position + the World it was taken in, per player. */
    private data class StartMarker(val x: Double, val y: Double, val z: Double, val world: String)

    private val startMarkers = HashMap<UUID, StartMarker>()

    /** The Portal's flag vocabulary, in its canonical (display) order. */
    private val VALID_FLAGS = listOf(
        "EMBASSY",
        "NO_SCOREBOARD",
        "ENABLE_EXPLOSIONS",
        "ADMIN",
        "ENABLE_PUBLIC_CONTAINERS",
        "DISABLE_GATES",
        "ENABLE_FIRE_DAMAGE",
        "DISABLE_PLAYER_FALL_DAMAGE",
        "ENABLE_PUBLIC_VILLAGER_TRADING",
        "DISABLE_PUBLIC_REDSTONE_TRIGGERS",
        "DISABLE_ANIMAL_PROTECTION",
        "PUBLIC",
    )

    private val NAME_REGEX = Regex("^[a-zA-Z0-9!_'?()#:,.+&@*\\- ]{3,30}$")

    private const val MAX_AREA = 5000
    private const val MAX_MEMBERS = 99
    private const val MIN_Y = -64
    private const val MAX_Y = 320
    private const val MIN_Y_SPAN = 16

    // The Portal's help panel (help always says /rg, whichever alias ran).
    private val HELP: Component = Paint(
        Paint.darkGray("--["), " ", Paint.green.bold("Region Commands"), " ", Paint.darkGray("]--"), "\n",
        Paint.gray(" - "), Paint.white("/rg rename <name>"), "\n",
        Paint.gray(" - "), Paint.white("/rg add <player>"), "\n",
        Paint.gray(" - "), Paint.white("/rg remove <player>"), "\n",
        Paint.gray(" - "), Paint.white("/rg delete"), "\n",
        Paint.gray(" - "), Paint.white("/rg start"), " ", Paint.gray("+ "), Paint.white("/rg end"), "\n",
        Paint.gray(" - "), Paint.white("/rg flag [flag]"), "\n",
        Paint.gray(" - "), Paint.white("/rg locate <name>"),
    )

    /** Tab-completes online player names, like the Portal's onlinePlayer parser. */
    private val onlinePlayerNames = SuggestionProvider<CommandSourceStack> { context, builder ->
        SharedSuggestionProvider.suggest(context.source.onlinePlayerNames, builder)
    }

    /**
     * Tab-completes the members of the region the sender is standing in, by
     * the prefix they have typed — `/rg remove`'s helper for names that are
     * often not online (and so never in the vanilla player suggestions).
     */
    private val regionMemberNames = SuggestionProvider<CommandSourceStack> { context, builder ->
        val typed = builder.remaining.lowercase()
        val player = context.source.player
        val region = player?.let(RegionTracker::regionOf)
        region?.members
            ?.mapNotNull { RegionsFeature.usernameFor(context.source.server, it) }
            ?.filter { it.lowercase().startsWith(typed) }
            ?.forEach(builder::suggest)
        builder.buildFuture()
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(tree("region"))
        dispatcher.register(tree("rg"))
    }

    /** Drops a player's `/rg start` marker (called when they disconnect). */
    fun clearStartMarker(uuid: UUID) {
        startMarkers.remove(uuid)
    }

    private fun tree(alias: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(alias)
            .executes { ctx -> reply(ctx) { HELP } }
            .then(
                Commands.literal("rename")
                    .executes { ctx -> reply(ctx) { Paint.usage("/$alias rename <name>") } }
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString())
                            .executes { ctx -> reply(ctx) { rename(it, StringArgumentType.getString(ctx, "name")) } },
                    ),
            )
            .then(
                Commands.literal("add")
                    .executes { ctx -> reply(ctx) { Paint.usage("/$alias add <player>") } }
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests(onlinePlayerNames)
                            .executes { ctx -> reply(ctx) { addMember(it, StringArgumentType.getString(ctx, "player")) } },
                    ),
            )
            .then(
                Commands.literal("remove")
                    .executes { ctx -> reply(ctx) { Paint.usage("/$alias remove <player>") } }
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests(regionMemberNames)
                            .executes { ctx -> reply(ctx) { removeMember(it, StringArgumentType.getString(ctx, "player")) } },
                    ),
            )
            .then(Commands.literal("delete").executes { ctx -> reply(ctx) { delete(it) } })
            .then(Commands.literal("start").executes { ctx -> reply(ctx) { start(it) } })
            .then(Commands.literal("end").executes { ctx -> reply(ctx) { end(it) } })
            .then(
                Commands.literal("flag")
                    .executes { ctx -> reply(ctx) { listFlags(it) } }
                    .then(
                        Commands.argument("flag", StringArgumentType.greedyString())
                            .executes { ctx -> reply(ctx) { toggleFlag(it, StringArgumentType.getString(ctx, "flag")) } },
                    ),
            )
            .then(
                Commands.literal("bounds")
                    .executes { ctx -> reply(ctx) { showBounds(it) } }
                    .then(
                        Commands.argument("min-y", IntegerArgumentType.integer())
                            .executes { ctx -> reply(ctx) { Paint.usage("/$alias bounds <min-y> <max-y>") } }
                            .then(
                                Commands.argument("max-y", IntegerArgumentType.integer())
                                    .executes { ctx ->
                                        reply(ctx) {
                                            setBounds(
                                                it,
                                                IntegerArgumentType.getInteger(ctx, "min-y"),
                                                IntegerArgumentType.getInteger(ctx, "max-y"),
                                            )
                                        }
                                    },
                            ),
                    ),
            )
            .then(
                Commands.literal("locate")
                    .executes { ctx -> reply(ctx) { Paint.usage("/$alias locate <name>") } }
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString())
                            .executes { ctx -> reply(ctx) { locate(it, StringArgumentType.getString(ctx, "name")) } },
                    ),
            )

    /**
     * Runs a handler for the sending player and sends them its reply, if any —
     * every Portal region command answered the sender with one message
     * (handlers that send more, like `/rg locate`'s list, reply null).
     */
    private inline fun reply(
        ctx: CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> Component?,
    ): Int {
        val player = ctx.source.playerOrException
        handler(player)?.let(player::sendSystemMessage)
        return Command.SINGLE_SUCCESS
    }

    // ---- lifecycle commands ----

    private fun start(player: ServerPlayer): Component {
        // The Portal captured the last move-packet position and errored with
        // "Position not available yet, please move first" before the first
        // packet; the server-side position always exists, so that error is
        // gone (see the ticket's deviation notes).
        val pos = player.position()
        startMarkers[player.uuid] = StartMarker(pos.x, pos.y, pos.z, legacyWorldOf(player))
        return Paint.success(
            "First point set!\n\nNow move over to the next point and do:\n",
            Paint.green("/rg end"),
        )
    }

    private fun end(player: ServerPlayer): Component {
        val service = RegionsFeature.requireService()
        val start = startMarkers[player.uuid]
            ?: return Paint.error("You must start first. Use /rg start")

        val world = legacyWorldOf(player)
        if (start.world != world) {
            return Paint.error("Regions may only be created in the same world.")
        }
        // Same World analog (the Portal's same-backend-server check). The
        // world strings above already encode the World, so this cannot fire
        // today; it is kept for the Portal's validation sequence.
        if (RegionWorlds.isSecondaryWorld(start.world) != RegionWorlds.isSecondaryWorld(world)) {
            return Paint.error("Regions may only be created on the same server. Use /rg start again.")
        }

        // Area on the raw positions, +1 per axis for inclusive block spans —
        // exactly the Portal's arithmetic.
        val end = player.position()
        val area = (abs(start.x - end.x) + 1) * (abs(start.z - end.z) + 1)
        // The Portal's exact comparison (`area <= 9` on the raw double), not a
        // rounded-up minimum: a fractional area like 9.5 is not "too small".
        if (area <= 9) {
            return Paint.error("Region too small")
        }
        if (area > MAX_AREA && !RegionsFeature.isAdmin(player)) {
            return Paint.error(
                "Region too large (${floor(area).toInt()} blocks). " +
                    "Limit is $MAX_AREA blocks. Ask an admin to create it.",
            )
        }

        // Through the guarded lookup: the embassies void is a region a player
        // is not a member of, so the parent checks below turn them away there
        // exactly as Nucleus did.
        val startRegion = RegionsFeature.regionAt(world, floorInt(start.x), floorInt(start.y), floorInt(start.z))
        val endRegion = RegionsFeature.regionAt(world, floorInt(end.x), floorInt(end.y), floorInt(end.z))
        // Both corners inside the same existing region ⇒ creating a sub-region
        // of it; that region and its ancestors are containers, not overlaps.
        val parent = startRegion?.takeIf { it === endRegion }

        val minX = minOf(floorInt(start.x), floorInt(end.x))
        val maxX = maxOf(floorInt(start.x), floorInt(end.x))
        val minZ = minOf(floorInt(start.z), floorInt(end.z))
        val maxZ = maxOf(floorInt(start.z), floorInt(end.z))
        service.firstIntersecting(world, minX, maxX, minZ, maxZ, excluding = parent)?.let {
            return Paint.error("Overlapping region ", Paint.red(it.title), "!")
        }

        if (parent != null) {
            if ("EMBASSY" in parent.flags) {
                return Paint.error("You cannot create a region inside an embassy")
            }
            if ("ADMIN" in parent.flags) {
                return Paint.error("You cannot create a region inside a region with admin flag")
            }
            if (!parent.isResident(player.uuid)) {
                return Paint.error("You are not a member of the parent region")
            }
        } else if (startRegion != null || endRegion != null) {
            // One corner inside an existing region (or the corners in two
            // different ones): unreachable now that the full-intersection scan
            // above catches every crossing, but kept as the Portal's fallback.
            return Paint.error("Overlapping region ", Paint.red((startRegion ?: endRegion)!!.title), "!")
        }

        val title = "${player.gameProfile.name}'s Place"
        val region = Region(
            title = title,
            world = world,
            startX = floorInt(start.x),
            startZ = floorInt(start.z),
            endX = floorInt(end.x),
            endZ = floorInt(end.z),
            // Full build height (deviation 2; the Portal wrote 255/15 here).
        )
        region.members.add(player.uuid)
        service.add(region, parent)
        startMarkers.remove(player.uuid)
        // The creator is standing in it: their sidebar comes up at once, rather
        // than on the next tick's sweep.
        RegionTracker.refresh(player)

        return Paint.success(
            "Region ", Paint.green(title),
            " created!\n\nYou can now rename the region:\n", Paint.green("/rg rename <name>"),
        )
    }

    private fun rename(player: ServerPlayer, name: String): Component {
        val region = RegionTracker.regionOf(player)
            ?: return Paint.error("You must stand in the region you want to rename")
        if (!region.isResident(player.uuid) && !RegionsFeature.isAdmin(player)) {
            return Paint.error("You are not a member of this region")
        }
        if (!name.matches(NAME_REGEX)) {
            return Paint.error("Invalid region name")
        }
        val oldTitle = region.title
        region.title = name
        RegionsFeature.requireService().save()
        RegionTracker.redraw(player.level().server, region)
        return Paint.success("Renamed ", Paint.green(oldTitle), " to ", Paint.green(name))
    }

    private fun delete(player: ServerPlayer): Component {
        val region = RegionTracker.regionOf(player)
            ?: return Paint.error("You must stand in the region you want to delete")
        if (!region.isResident(player.uuid) && !RegionsFeature.isAdmin(player)) {
            return Paint.error("You are not a member of this region")
        }
        if ("EMBASSY" in region.flags) {
            return Paint.error("You must use ", Paint.red("/embassy delete"), " to delete an embassy")
        }
        val title = region.title
        RegionTracker.clear(player.level().server, region)
        RegionsFeature.requireService().remove(region)
        return Paint.success("Deleted region ", Paint.green(title))
    }

    // ---- membership commands ----

    private fun addMember(player: ServerPlayer, targetName: String): Component {
        // The Portal resolved the online-player argument before the command
        // body ran, so an unknown target is answered ahead of every guard.
        val server = player.level().server
        val target = server.playerList.getPlayerByName(targetName)
            ?: return Paint.gray("Player ", Paint.red(targetName), " not found or is offline")
        val region = RegionTracker.regionOf(player)
            ?: return Paint.error("You must stand in the region you want to add a resident to")
        if (region.members.size >= MAX_MEMBERS) {
            return Paint.error("Regions may only have $MAX_MEMBERS members")
        }
        // Residents and admins may add — and so may a resident of the region
        // one level up, who is landlord to this sub-region.
        if (!region.isResident(player.uuid) &&
            !RegionsFeature.isAdmin(player) &&
            region.parent?.isResident(player.uuid) != true
        ) {
            return Paint.error("You are not a member of this region")
        }
        val name = target.gameProfile.name
        if (region.isResident(target.uuid)) {
            return Paint.error(Paint.red(name), " is already a member of ", Paint.red(region.title))
        }
        region.members.add(target.uuid)
        RegionsFeature.requireService().save()
        RegionTracker.redraw(server, region)
        return Paint.success(Paint.green(name), " has been added to ", Paint.green(region.title))
    }

    private fun removeMember(player: ServerPlayer, targetName: String): Component {
        val region = RegionTracker.regionOf(player)
            ?: return Paint.error("You must stand in the region you want to remove a resident from")
        if (!region.isResident(player.uuid) && !RegionsFeature.isAdmin(player)) {
            return Paint.error("You are not a member of this region")
        }
        // Members are named, not picked from the online players: the target is
        // whichever member's name matches what was typed, in any casing.
        val server = player.level().server
        val target = region.members.firstOrNull {
            RegionsFeature.usernameFor(server, it).equals(targetName, ignoreCase = true)
        } ?: return Paint.error(Paint.red(targetName), " is not a member of ", Paint.red(region.title))
        if (region.members.size == 1) {
            return Paint.error(Paint.red(targetName), " is the only member of ", Paint.red(region.title))
        }
        region.members.remove(target)
        RegionsFeature.requireService().save()
        RegionTracker.redraw(server, region)
        return Paint.success(Paint.green(targetName), " has been removed from ", Paint.green(region.title))
    }

    // ---- admin commands ----

    /** The Portal's gate reply for every admin-only command, or null to proceed. */
    private fun adminGate(player: ServerPlayer): Component? = RegionsFeature.adminGate(player)

    private fun toggleFlag(player: ServerPlayer, rawFlag: String): Component {
        adminGate(player)?.let { return it }
        val region = RegionTracker.regionOf(player)
            ?: return Paint.error("You must stand in the region you want to toggle a flag on")
        val flag = rawFlag.uppercase()
        if (flag !in VALID_FLAGS) {
            return Paint.error("Invalid flag. Valid flags: ${VALID_FLAGS.joinToString(", ")}")
        }
        if (flag == "EMBASSY") {
            return Paint.error("You cannot toggle the embassy flag")
        }
        val added = region.flags.add(flag)
        if (!added) region.flags.remove(flag)
        RegionsFeature.requireService().save()
        // NO_SCOREBOARD decides whether the sidebar is drawn at all, so a
        // toggle takes effect on the occupants now rather than next time they
        // walk in (the Portal left the stale board up).
        RegionTracker.redraw(player.level().server, region)
        return Paint.success("Flag ", Paint.green(flag), if (added) " added" else " removed")
    }

    private fun listFlags(player: ServerPlayer): Component {
        adminGate(player)?.let { return it }
        val region = RegionTracker.regionOf(player)
            ?: return Paint.error("You must stand in a region to view flags")
        // Enabled flags in green then disabled in red, each group in the
        // canonical order, comma-separated on a gray line.
        val (enabled, disabled) = VALID_FLAGS.partition { it in region.flags }
        val coloured = enabled.map { Paint.green(it) } + disabled.map { Paint.red(it) }
        val parts = mutableListOf<Any>("Flags: ")
        coloured.forEachIndexed { index, flag ->
            if (index > 0) parts.add(", ")
            parts.add(flag)
        }
        return Paint.gray(*parts.toTypedArray())
    }

    private fun setBounds(player: ServerPlayer, rawMinY: Int, rawMaxY: Int): Component {
        adminGate(player)?.let { return it }
        val region = RegionTracker.regionOf(player)
            ?: return Paint.error("You must stand in the region you want to set bounds for")
        val minY = minOf(rawMinY, rawMaxY)
        val maxY = maxOf(rawMinY, rawMaxY)
        if (minY < MIN_Y || maxY > MAX_Y) {
            return Paint.error("Y bounds must be between $MIN_Y and $MAX_Y")
        }
        if (maxY - minY < MIN_Y_SPAN) {
            return Paint.error("Y bounds must be at least $MIN_Y_SPAN blocks tall")
        }
        region.startY = maxY
        region.endY = minY
        RegionsFeature.requireService().save()
        return Paint.success(
            "Set Y bounds for ", Paint.green(region.title),
            " to ", Paint.white(minY), " - ", Paint.white(maxY),
        )
    }

    private fun showBounds(player: ServerPlayer): Component {
        adminGate(player)?.let { return it }
        val region = RegionTracker.regionOf(player)
            ?: return Paint.error("You must stand in a region to view bounds")
        return Paint(
            Paint.green(region.title),
            " bounds: Y ", Paint.white(region.minY), " to ", Paint.white(region.maxY),
        )
    }

    private fun locate(player: ServerPlayer, query: String): Component? {
        adminGate(player)?.let { return it }
        val server = player.level().server
        val found = RegionsFeature.requireService().search(query) { uuid ->
            RegionsFeature.usernameFor(server, uuid)
        }
        if (found.isEmpty()) {
            return Paint.error("No regions found matching \"$query\"")
        }
        if (found.size == 1) {
            val region = found.single()
            return Paint(
                Paint.yellow(region.title),
                " - ", Paint.white("${centerX(region)}/~/${centerZ(region)}"),
                "/", Paint.green(RegionWorlds.locateInfo(region.world)),
            )
        }
        player.sendSystemMessage(Paint("Located regions (", Paint.yellow(found.size), "):"))
        for (region in found.take(10)) {
            player.sendSystemMessage(
                Paint(
                    " - ", Paint.yellow(region.title), " ",
                    Paint.gray("${centerX(region)}/${centerZ(region)}/${RegionWorlds.locateInfo(region.world)}"),
                ),
            )
        }
        if (found.size > 10) {
            player.sendSystemMessage(Paint.gray(" ...and ${found.size - 10} more"))
        }
        return null
    }

    // ---- shared lookups ----

    private fun legacyWorldOf(player: ServerPlayer): String =
        RegionWorlds.legacyName(player.level().dimension())

    private fun floorInt(value: Double): Int = floor(value).toInt()

    // The Portal's center arithmetic: Math.floor of the (possibly .5) midpoint.
    private fun centerX(region: Region): Int = Math.floorDiv(region.startX + region.endX, 2)

    private fun centerZ(region: Region): Int = Math.floorDiv(region.startZ + region.endZ, 2)
}
