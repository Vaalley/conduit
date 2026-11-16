package eu.mctraveler.rtp

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import eu.mctraveler.command.Audience
import eu.mctraveler.crystal.CrystalSpawns
import eu.mctraveler.dragonfight.DragonFightFeature
import eu.mctraveler.passport.PassportFeature
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import eu.mctraveler.worlds.TeleportCountdown
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.phys.BlockHitResult

/**
 * `/wilderness` (it used to be `/rtp`): sends the player to a random safe spot, after the
 * usual [TeleportCountdown], once per cooldown. In the overworld the spot lies in the ring
 * [RtpConfig] describes around the second spawn; `/wilderness end` does the same in the End.
 *
 * It is also what a `<wilderness>` sign ([RtpSigns]) and a physical portal
 * ([WildernessPortals], built with `/wilderness portal create`) do: the sign on a
 * right-click, the portal the moment someone steps into it. Either sends a player in the
 * End to the End.
 */
object RtpFeature {

    const val NAME = "wilderness"

    /** How far from the portal an admin may be for `/wilderness portal remove` to find it. */
    private const val PORTAL_REMOVE_RANGE = 48.0

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            RtpConfig.reload()
            registerCommand(dispatcher)
        }
        UseBlockCallback.EVENT.register(::onUseBlock)
        ServerLifecycleEvents.SERVER_STOPPED.register { RtpCooldown.clear() }
        WildernessPortals.register()
        PortalSelector.register()
    }

    private fun registerCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal(NAME)
                .executes { ctx -> reply(ctx) { teleport(it) } }
                .then(
                    Commands.literal("end")
                        .executes { ctx -> reply(ctx) { teleportEnd(it) } },
                )
                .then(
                    Commands.literal("portal")
                        .requires(Audience.STAFF.gate)
                        .executes { ctx -> reply(ctx) { Paint.usage("/$NAME portal <create|accept|cancel|remove>") } }
                        .then(Commands.literal("create").executes { ctx -> reply(ctx) { staffOnly(it, PortalSelector::give) } })
                        .then(Commands.literal("accept").executes { ctx -> reply(ctx) { staffOnly(it, PortalSelector::accept) } })
                        .then(Commands.literal("cancel").executes { ctx -> reply(ctx) { staffOnly(it, PortalSelector::cancel) } })
                        .then(Commands.literal("remove").executes { ctx -> reply(ctx) { staffOnly(it, ::removeNearestPortal) } }),
                ),
        )
    }

    private fun staffOnly(player: ServerPlayer, action: (ServerPlayer) -> Component): Component =
        RegionsFeature.adminGate(player) ?: action(player)

    /** Runs [action] now, or after the teleport countdown. */
    private fun begin(player: ServerPlayer, instant: Boolean, action: () -> Unit) {
        if (instant) action() else TeleportCountdown.begin(player, action)
    }

    /**
     * The whole of `/wilderness` for [player]: the gates that answer at once, then the
     * countdown ([instant] skips it: a portal does not make you wait), at whose end the
     * spot is picked and the cooldown started. Returns the immediate reply, or null when
     * the countdown is under way.
     */
    fun teleport(player: ServerPlayer, instant: Boolean = false): Component? {
        val level = player.level()
        if (level.dimension() != Level.OVERWORLD) {
            return Paint.error("Random teleport only works in the overworld")
        }
        val settings = RtpConfig.settings()
        val now = level.server.tickCount
        val admin = RegionsFeature.isAdmin(player)
        val waiting = RtpCooldown.remaining(player.uuid, now)
        if (!admin && waiting > 0) {
            return Paint.error("You can use /$NAME again in ", Paint.gold(RtpCooldown.format(waiting)))
        }
        if (TeleportCountdown.isCounting(player.uuid)) {
            return Paint.error("You are already teleporting")
        }
        begin(player, instant) {
            val (centerX, centerZ) = overworldCentre(level)
            val landing = RtpPicker.pick(
                player,
                level,
                settings.overworldRing,
                RtpPicker.MAX_ATTEMPTS,
                centerX,
                centerZ,
            )
            if (landing == null) {
                player.sendSystemMessage(Paint.error("No safe spot was found, please try again"))
                return@begin
            }
            if (!landing.send(player)) return@begin
            player.resetFallDistance()
            PassportFeature.recordRtpUse(player)
            if (!admin) RtpCooldown.start(player.uuid, level.server.tickCount, settings.cooldownTicks)
            player.sendSystemMessage(
                Paint.success(
                    "Teleported to ",
                    Paint.green(Mth.floor(landing.x)),
                    ", ",
                    Paint.green(Mth.floor(landing.z)),
                ),
            )
        }
        return null
    }

    /** The overworld ring is centred on the second spawn (the world spawn if there is none). */
    private fun overworldCentre(level: net.minecraft.server.level.ServerLevel): Pair<Int, Int> {
        val spawn = CrystalSpawns.definitions().getOrNull(1)
        if (spawn != null) return Mth.floor(spawn.x) to Mth.floor(spawn.z)
        val world = level.respawnData.pos()
        return world.x to world.z
    }

    fun teleportEnd(player: ServerPlayer, instant: Boolean = false): Component? {
        if (!RegionsFeature.isAdmin(player) && DragonFightFeature.state?.isCompleted(player.uuid) != true) {
            return Paint.error("Free the End first (/dragonfight)")
        }
        val level = player.level()
        if (level.dimension() != Level.OVERWORLD && level.dimension() != Level.END) {
            return Paint.error("/$NAME end only works from the overworld or the End")
        }
        val settings = RtpConfig.settings()
        val now = level.server.tickCount
        val admin = RegionsFeature.isAdmin(player)
        val waiting = RtpCooldown.remaining(player.uuid, now, RtpKind.END)
        if (!admin && waiting > 0) {
            return Paint.error("You can use /$NAME end again in ", Paint.gold(RtpCooldown.format(waiting)))
        }
        if (TeleportCountdown.isCounting(player.uuid)) {
            return Paint.error("You are already teleporting")
        }
        begin(player, instant) {
            val destination = level.server.getLevel(Level.END)
            if (destination == null) {
                player.sendSystemMessage(Paint.error("No safe spot was found, please try again"))
                return@begin
            }
            val landing = RtpPicker.pick(
                player,
                destination,
                settings.endRing,
                RtpPicker.END_MAX_ATTEMPTS,
                0,
                0,
            )
            if (landing == null) {
                player.sendSystemMessage(Paint.error("No safe spot was found, please try again"))
                return@begin
            }
            if (!landing.send(player)) return@begin
            player.resetFallDistance()
            PassportFeature.recordRtpUse(player)
            if (!admin) RtpCooldown.start(player.uuid, level.server.tickCount, settings.endCooldownTicks, RtpKind.END)
            player.sendSystemMessage(
                Paint.success(
                    "Teleported to ",
                    Paint.green(Mth.floor(landing.x)),
                    ", ",
                    Paint.green(Mth.floor(landing.z)),
                ),
            )
        }
        return null
    }

    /**
     * What a sign or a portal in [player]'s dimension does: the End teleport in the End, the
     * overworld one anywhere else.
     */
    private fun teleportHere(player: ServerPlayer, instant: Boolean): Component? =
        if (player.level().dimension() == Level.END) teleportEnd(player, instant) else teleport(player, instant)

    /** A player has just stepped into a wilderness portal. */
    fun enterPortal(player: ServerPlayer) {
        teleportHere(player, instant = true)?.let(player::sendSystemMessage)
    }

    /** `/wilderness portal remove`: drains the wilderness portal nearest to the admin. */
    private fun removeNearestPortal(player: ServerPlayer): Component {
        val portal = WildernessPortals.nearest(player, PORTAL_REMOVE_RANGE)
            ?: return Paint.error("There is no wilderness portal within ${PORTAL_REMOVE_RANGE.toInt()} blocks of you")
        WildernessPortals.remove(player.level(), portal)
        return Paint.success("Removed the wilderness portal (${portal.box.volume} blocks)")
    }

    private fun onUseBlock(
        player: Player,
        level: Level,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        if (player !is ServerPlayer || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        val sign = level.getBlockEntity(hit.blockPos) as? SignBlockEntity ?: return InteractionResult.PASS
        if (!RtpSigns.isMarked(sign)) return InteractionResult.PASS
        teleportHere(player, instant = false)?.let(player::sendSystemMessage)
        return InteractionResult.SUCCESS_SERVER
    }

    private inline fun reply(
        ctx: CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> Component?,
    ): Int {
        val player = ctx.source.playerOrException
        handler(player)?.let(player::sendSystemMessage)
        return Command.SINGLE_SUCCESS
    }
}
