package eu.mctraveler.rtp

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
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
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * Random teleport: `/rtp` sends the player to a random safe spot in the
 * overworld ring [RtpConfig] describes, after the usual [TeleportCountdown],
 * once per cooldown. Admins skip the cooldown and may turn any sign into an
 * [RtpSigns] sign with `/rtp sign`, which does the same on right-click.
 */
object RtpFeature {

    const val NAME = "rtp"

    /** How far away an admin may be from the sign `/rtp sign` marks. */
    private const val SIGN_REACH = 6.0

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            RtpConfig.reload()
            registerCommand(dispatcher)
        }
        UseBlockCallback.EVENT.register(::onUseBlock)
        ServerLifecycleEvents.SERVER_STOPPED.register { RtpCooldown.clear() }
    }

    private fun registerCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal(NAME)
                .executes { ctx -> reply(ctx, ::teleport) }
                .then(
                    Commands.literal("sign")
                        .executes { ctx -> reply(ctx, ::toggleSign) },
                ),
        )
    }

    /**
     * The whole of `/rtp` for [player]: the gates that answer at once, then the
     * countdown, at whose end the spot is picked and the cooldown started.
     * Returns the immediate reply, or null when the countdown is under way.
     */
    fun teleport(player: ServerPlayer): Component? {
        val level = player.level()
        if (level.dimension() != Level.OVERWORLD) {
            return Paint.error("Random teleport only works in the overworld")
        }
        val settings = RtpConfig.settings()
        val now = level.server.tickCount
        val admin = RegionsFeature.isAdmin(player)
        val waiting = RtpCooldown.remaining(player.uuid, now)
        if (!admin && waiting > 0) {
            return Paint.error("You can use /rtp again in ", Paint.gold(RtpCooldown.format(waiting)))
        }
        if (TeleportCountdown.isCounting(player.uuid)) {
            return Paint.error("You are already teleporting")
        }
        TeleportCountdown.begin(player) {
            val landing = RtpPicker.pick(player, level, settings)
            if (landing == null) {
                player.sendSystemMessage(Paint.error("No safe spot was found, please try again"))
                return@begin
            }
            if (!landing.send(player)) return@begin
            player.resetFallDistance()
            if (!admin) RtpCooldown.start(player.uuid, level.server.tickCount, settings.cooldownTicks)
            player.sendSystemMessage(
                Paint.success(
                    "Teleported to ",
                    Paint.green(landing.x.toInt()),
                    ", ",
                    Paint.green(landing.z.toInt()),
                ),
            )
        }
        return null
    }

    /** `/rtp sign`: marks the sign the admin is looking at, or unmarks a marked one. */
    private fun toggleSign(player: ServerPlayer): Component {
        RegionsFeature.adminGate(player)?.let { return it }
        // Partial tick 1: where the player is now, not where the last tick left them.
        val hit = player.pick(SIGN_REACH, 1.0f, false)
        val sign = (hit as? BlockHitResult)
            ?.takeIf { hit.type == HitResult.Type.BLOCK }
            ?.let { player.level().getBlockEntity(it.blockPos) as? SignBlockEntity }
            ?: return Paint.error("Look at a sign to make it a random teleport sign")
        return if (RtpSigns.mark(sign)) {
            Paint.success("This sign now teleports players randomly")
        } else {
            RtpSigns.unmark(sign)
            Paint.success("This sign is a plain sign again")
        }
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
        teleport(player)?.let(player::sendSystemMessage)
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
