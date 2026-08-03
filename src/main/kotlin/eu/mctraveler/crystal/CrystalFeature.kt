package eu.mctraveler.crystal

import eu.mctraveler.region.RegionProtection
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult

/**
 * Wiring for the Teleportation Crystal (spec User Stories 20-37).
 *
 * The item, its recipes and the damage-bar rewrite need no runtime wiring at
 * all — recipes are datapack files and the rest is mixins. What lives here is
 * the recharge loop, the admin command, the right-click that opens the menu,
 * and the lifecycle story 36 asks for.
 */
object CrystalFeature {

    /**
     * How often the recharge loop looks at the online players. A second is
     * plenty for a fifteen-minute clock, and it is Nucleus's own cadence.
     */
    const val REGEN_CHECK_INTERVAL_TICKS = 20

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            CrystalSpawns.start(server.serverDirectory)
        }
        ServerTickEvents.END_SERVER_TICK.register(::onEndServerTick)
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            CrystalCommands.register(dispatcher)
        }

        // A crystal works standing anywhere, on anyone's land (spec story 26,
        // deviation 13), and its menus are the mod's own (deviation 16).
        RegionProtection.exemptItem(CrystalItem::isCrystal)
        RegionProtection.exemptMenu { it is CrystalMenu.CrystalChestMenu }

        // Right-clicking a crystal: at air, and at a block.
        UseItemCallback.EVENT.register { player, _, hand -> onUseItem(player, hand) }
        UseBlockCallback.EVENT.register(::onUseBlock)

        // Story 36's lifecycle. The open menu is the whole of the per-player
        // state, so closing it is all there is to do; only the requests are
        // book-keeping of our own.
        ServerLifecycleEvents.SERVER_STOPPING.register(CrystalMenu::closeAll)
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            CrystalRequests.forget(handler.player.uuid)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { CrystalRequests.clear() }
    }

    /** Right-click holding a crystal, at nothing in particular. */
    private fun onUseItem(player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (player !is ServerPlayer || !CrystalItem.isCrystal(stack)) return InteractionResult.PASS
        CrystalMenu.use(player, CrystalItem.tierOf(stack))
        // Anything but PASS also takes the vanilla use of the shard away.
        return InteractionResult.SUCCESS_SERVER
    }

    /**
     * Right-click holding a crystal, at a block — and the crystal wins the
     * click, whatever the block would have done with it.
     *
     * This is why the hook is [UseBlockCallback] rather than the narrower
     * `ItemEvents.USE_ON`. Vanilla's `useItemOn` runs the *block's* behaviour
     * first (`useItemOn`, then `useWithoutItem`) and only reaches the item's own
     * `useOn` if neither consumed the click, so a crystal aimed at a chest would
     * open the chest and the item event would never fire.
     * [UseBlockCallback] is ahead of all of that, which is where Nucleus stood:
     * it cancelled Bukkit's `PlayerInteractEvent` before the chest ever opened.
     *
     * Only the hand the click came in on is considered, which is Nucleus's
     * `e.item` — its interact event fired per hand and read that hand's item.
     * The visible consequence is that a crystal in the *off* hand with an empty
     * main hand loses to an interactive block, because vanilla resolves the
     * empty main hand against the block first and never asks the off hand.
     * Nucleus behaved the same way, so it stays.
     */
    private fun onUseBlock(
        player: Player,
        level: Level,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (player !is ServerPlayer || !CrystalItem.isCrystal(stack)) return InteractionResult.PASS
        CrystalMenu.use(player, CrystalItem.tierOf(stack))
        // Anything but PASS cancels the block interaction outright.
        return InteractionResult.SUCCESS_SERVER
    }

    /**
     * Hands out recharges that have come due. Only online players are
     * considered — the clock is play time, so an offline player's threshold is
     * not getting any closer.
     */
    private fun onEndServerTick(server: MinecraftServer) {
        CrystalRequests.sweep(server)
        if (server.tickCount % REGEN_CHECK_INTERVAL_TICKS != 0) return
        for (player in server.playerList.players) {
            if (!CrystalEnergy.regen(player)) continue
            player.sendSystemMessage(Paint.info("Your energy crystal has recharged one energy"))
        }
    }
}
