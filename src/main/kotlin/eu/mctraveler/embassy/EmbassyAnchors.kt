package eu.mctraveler.embassy

import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionTracker
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.util.UUID
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.BlockEvents
import net.fabricmc.fabric.api.event.player.ItemEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock

/**
 * The respawn anchor in the middle of every embassy: the teleporter a visitor
 * steps onto (spec stories 13-14), and the guard that stops anyone blowing it
 * up (story 15).
 *
 * The anchor is the plot's whole point — stand on it and the embassy sends you
 * to the place its owner recorded when they created it. The dimension declares
 * `respawn_anchor_works: false`, so vanilla's answer to a right-click on a
 * charged anchor is to detonate it; the guard is what keeps that from ever
 * being the answer inside an embassy.
 */
object EmbassyAnchors {

    /**
     * The block each player last stood on, while they are in the dimension.
     * Nucleus acted on a move event gated by `hasChangedBlock()`; the sweep
     * below is that gate, and this is the "from" side of it.
     */
    private val lastBlock = HashMap<UUID, BlockPos>()

    fun register() {
        // A once-a-tick sweep rather than a movement hook, matching the shape
        // ticket 01 used for void-falls: a player standing on an anchor is
        // standing on it however they got there.
        ServerTickEvents.END_SERVER_TICK.register { server ->
            for (player in server.playerList.players) {
                if (!EmbassiesFeature.isEmbassies(player.level())) {
                    lastBlock.remove(player.uuid)
                    continue
                }
                val now = player.blockPosition()
                val previous = lastBlock.put(player.uuid, now)
                // Nothing to compare against yet: the player has just arrived,
                // and being *put* on an anchor is not stepping onto one. This
                // is what lets `/embassy create` drop its sender in the middle
                // of the plot it just built without bouncing them straight out.
                if (previous == null || previous == now) continue
                steppedOn(player, now)
            }
        }

        // Both halves of a right-click on a block: an item applied to it, and
        // the block's own behaviour with an empty hand. Region protection
        // already refuses the first to a visitor, but it lets the second
        // through for everyone — an anchor is neither a door nor a switch — so
        // without this a stranger could walk in and detonate the plot.
        ItemEvents.USE_ON.register { context ->
            val player = context.player
            if (player is ServerPlayer && !allowsAnchorUse(player, context.level, context.clickedPos)) {
                InteractionResult.FAIL
            } else {
                null
            }
        }
        BlockEvents.USE_WITHOUT_ITEM.register { _, level, pos, player, _ ->
            if (player is ServerPlayer && !allowsAnchorUse(player, level, pos)) {
                InteractionResult.FAIL
            } else {
                null
            }
        }

        // LEAVE, not DISCONNECT: it is the head of the player list's removal,
        // so it fires however a session ends (ticket 01's deviation 5).
        ServerPlayerEvents.LEAVE.register { player -> lastBlock.remove(player.uuid) }
        ServerLifecycleEvents.SERVER_STOPPED.register { lastBlock.clear() }
    }

    /**
     * Whether [player] may right-click the block at [pos]. Only ever refuses an
     * anchor inside an embassy, and only when the click would do something
     * destructive: an empty anchor has nothing to lose, and topping up an
     * under-charged one with glowstone is the one interaction it is for.
     *
     * Silent, as Nucleus's was — the anchor simply does not respond.
     */
    fun allowsAnchorUse(player: ServerPlayer, level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (!state.`is`(Blocks.RESPAWN_ANCHOR)) return true
        val region = RegionsFeature.regionAt(level, pos) ?: return true
        if (Region.EMBASSY_FLAG !in region.flags) return true

        val charges = state.getValue(RespawnAnchorBlock.CHARGE)
        if (charges == 0) return true
        // Nucleus read the main hand only, so charging from the off-hand was
        // refused just as it is here.
        if (player.mainHandItem.`is`(Items.GLOWSTONE) && charges < EmbassyPlots.ANCHOR_CHARGES) return true
        return false
    }

    /** [player] has just stepped onto the block at [feet]. */
    private fun steppedOn(player: ServerPlayer, feet: BlockPos) {
        val region = RegionTracker.regionOf(player) ?: return
        if (Region.EMBASSY_FLAG !in region.flags) return
        if (!player.level().getBlockState(feet.below()).`is`(Blocks.RESPAWN_ANCHOR)) return

        if (player.isShiftKeyDown) {
            player.sendSystemMessage(Paint.info("Sneaking, teleportation ignored"))
            return
        }

        val landing = EmbassyDestination.of(region)?.resolve(player.level().server) ?: return

        // The way back is offered before the trip, so an admin who lands
        // somewhere unexpected still has the line in front of them.
        if (RegionsFeature.isAdmin(player)) sendBackLink(player)
        landing.send(player)
        player.sendSystemMessage(Paint.success("Teleported from embassy"))
    }

    /**
     * The admin's clickable way back to where they are standing (story 14,
     * deviation 3): Nucleus ran Bukkit's `/tp <x> <y> <z> <world>`, which has
     * no vanilla equivalent, so the same trip is spelled as an `/execute in`.
     *
     * The whole line is clickable, as Nucleus's was — the click event sits on
     * the message body, and "here" is the aqua inside it.
     */
    private fun sendBackLink(player: ServerPlayer) {
        val back = "/execute in ${player.level().dimension().identifier()} run tp @s " +
            "${player.blockX} ${player.blockY} ${player.blockZ}"
        player.sendSystemMessage(
            Paint.info(
                Paint.runs(back)(
                    "You can click ",
                    Paint.aqua("here"),
                    " to go back to your previous location.",
                ),
            ),
        )
    }

}
