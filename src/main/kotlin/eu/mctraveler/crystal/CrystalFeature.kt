package eu.mctraveler.crystal

import eu.mctraveler.region.RegionProtection
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.ItemEvents
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.UseOnContext

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
        ItemEvents.USE_ON.register(::onUseOn)

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
     * Right-click holding a crystal, at a block. The block's own behaviour is a
     * separate, earlier step in vanilla, so a crystal aimed at a chest still
     * opens the chest — as it would with any other item in hand.
     */
    private fun onUseOn(context: UseOnContext): InteractionResult? {
        val player = context.player
        val stack = context.itemInHand
        if (player !is ServerPlayer || !CrystalItem.isCrystal(stack)) return null
        CrystalMenu.use(player, CrystalItem.tierOf(stack))
        // This event continues on null; anything else cancels.
        return InteractionResult.SUCCESS_SERVER
    }

    /**
     * Hands out recharges that have come due. Only online players are
     * considered — the clock is play time, so an offline player's threshold is
     * not getting any closer.
     */
    private fun onEndServerTick(server: MinecraftServer) {
        if (server.tickCount % REGEN_CHECK_INTERVAL_TICKS != 0) return
        for (player in server.playerList.players) {
            if (!CrystalEnergy.regen(player)) continue
            player.sendSystemMessage(Paint.info("Your energy crystal has recharged one energy"))
        }
    }
}
