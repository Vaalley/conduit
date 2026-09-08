package eu.mctraveler.moderation

import com.mojang.brigadier.arguments.StringArgumentType
import eu.mctraveler.MCTraveler
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack

object InvseeMenu {
    private const val SIZE = 54
    private var tickRegistered = false

    fun registerCommand(
        dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>,
        gate: (CommandSourceStack) -> Boolean,
    ) {
        if (!tickRegistered) {
            tickRegistered = true
            ServerTickEvents.END_SERVER_TICK.register { server ->
                if (server.tickCount % 20 != 0) return@register
                server.playerList.players.mapNotNull { it.containerMenu as? InvseeChestMenu }.forEach { it.refresh() }
            }
        }
        dispatcher.register(
            Commands.literal("invsee").requires(gate)
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .executes { context ->
                            val viewer = context.source.playerOrException
                            if (!ModerationFeature.authorized(context.source)) return@executes 0
                            val name = StringArgumentType.getString(context, "player")
                            val target = context.source.server.playerList.getPlayerByName(name)
                            if (target == null) {
                                viewer.sendSystemMessage(Paint.error("Player ", Paint.red(name), " is offline"))
                                return@executes 0
                            }
                            open(viewer, target)
                            1
                        },
                ),
        )
    }

    private fun open(viewer: ServerPlayer, target: ServerPlayer) {
        if (viewer.containerMenu is InvseeChestMenu) return
        val contents = SimpleContainer(SIZE)
        fill(contents, target)
        viewer.openMenu(
            SimpleMenuProvider(
                { id, inventory, _ -> InvseeChestMenu(id, inventory, contents, target, viewer) },
                Component.literal("Inventory of ${target.gameProfile.name}"),
            ),
        )
    }

    private fun fill(contents: Container, target: ServerPlayer) {
        for (i in 0 until SIZE) contents.setItem(i, ItemStack.EMPTY)
        for (slot in 0..26) contents.setItem(slot, target.inventory.getItem(slot + 9).copy())
        for (slot in 0..8) contents.setItem(27 + slot, target.inventory.getItem(slot).copy())
        for (slot in 0..4) contents.setItem(36 + slot, target.inventory.getItem(36 + slot).copy())
    }

    class InvseeChestMenu(
        containerId: Int,
        inventory: Inventory,
        private val contents: Container,
        private val target: ServerPlayer,
        private val viewer: ServerPlayer,
    ) : ChestMenu(MenuType.GENERIC_9x6, containerId, inventory, contents, 6) {
        override fun clicked(slot: Int, button: Int, input: ContainerInput, player: Player) = Unit
        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY
        fun refresh() {
            if (viewer.containerMenu !== this) return
            fill(contents, target)
            broadcastChanges()
        }
    }
}
