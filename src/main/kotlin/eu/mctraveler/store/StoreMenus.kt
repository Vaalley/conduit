package eu.mctraveler.store

import eu.mctraveler.economy.Economy
import eu.mctraveler.economy.Reasons
import eu.mctraveler.text.Paint
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

object StoreMenus {
    fun openStock(player: ServerPlayer, record: StoreRecord) {
        val sold = StoreFeature.recordItem(player, record)
        val rows = record.rows.coerceIn(3, 6)
        val contents = SimpleContainer(rows * 9)
        var remaining = record.stock
        for (slot in 0 until contents.containerSize) {
            if (remaining == 0) break
            val count = remaining.coerceAtMost(sold.maxStackSize)
            contents.setItem(slot, sold.copyWithCount(count))
            remaining -= count
        }
        player.openMenu(
            SimpleMenuProvider(
                { id, inventory, _ -> StockMenu(id, inventory, contents, record.frameId, sold, rows) },
                Component.literal("Store stock"),
            ),
        )
    }

    fun openBuy(player: ServerPlayer, record: StoreRecord) {
        player.openMenu(
            SimpleMenuProvider(
                { id, inventory, _ ->
                    BuyMenu(id, inventory, player, record.frameId).also { fillBuy(player, it, record) }
                },
                Component.literal(title(player, record)),
            ),
        )
    }

    fun openSell(player: ServerPlayer, record: StoreRecord) {
        player.openMenu(
            SimpleMenuProvider(
                { id, inventory, _ ->
                    SellMenu(id, inventory, player, record.frameId).also { fillSell(player, it, record) }
                },
                Component.literal(sellTitle(player, record)),
            ),
        )
    }

    private fun fillBuy(player: ServerPlayer, menu: BuyMenu, record: StoreRecord) {
        val sold = StoreFeature.recordItem(player, record)
        for ((slot, quantity) in StoreLadder.QUANTITIES.withIndex()) {
            menu.contents.setItem(
                slot,
                if (record.stock >= quantity) {
                    sold.copyWithCount(quantity).apply {
                        set(DataComponents.MAX_STACK_SIZE, 99)
                        set(
                            DataComponents.CUSTOM_NAME,
                            Component.literal(Economy.format(StoreLadder.priceFor(record.pricePerItem, quantity)))
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withItalic(false)),
                        )
                        set(
                            DataComponents.LORE,
                            ItemLore(listOf(Component.literal("Click to buy $quantity")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)))),
                        )
                    }
                } else {
                    ItemStack(Items.COBWEB, quantity).apply {
                        set(
                            DataComponents.CUSTOM_NAME,
                            Component.literal("Out of stock")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withItalic(false)),
                        )
                    }
                },
            )
        }
    }

    private fun title(player: ServerPlayer, record: StoreRecord): String =
        "${StoreFeature.recordItem(player, record).hoverName.string} — ${Economy.format(record.pricePerItem)} each"

    private fun sellTitle(player: ServerPlayer, record: StoreRecord): String =
        "Buying ${StoreFeature.recordItem(player, record).hoverName.string} — ${Economy.format(record.pricePerItem)} each"

    class StockMenu(
        containerId: Int,
        inventory: Inventory,
        private val contents: Container,
        private val frameId: java.util.UUID,
        private val sold: ItemStack,
        rows: Int,
    ) : ChestMenu(menuType(rows), containerId, inventory, contents, rows) {
        override fun removed(player: Player) {
            var stock = 0
            for (slot in 0 until contents.containerSize) {
                val stack = contents.getItem(slot)
                if (stack.isEmpty) continue
                if (ItemStack.isSameItemSameComponents(stack, sold)) {
                    stock += stack.count
                } else if (player is ServerPlayer) {
                    player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
                }
                contents.setItem(slot, ItemStack.EMPTY)
            }
            StoreFeature.requireService().byFrame(frameId)?.let {
                it.stock = stock.coerceAtMost(it.capacity)
                StoreFeature.requireService().save()
            }
            super.removed(player)
        }

        companion object {
            private fun menuType(rows: Int): MenuType<ChestMenu> =
                if (rows <= 3) MenuType.GENERIC_9x3 else MenuType.GENERIC_9x6
        }
    }

    class BuyMenu(
        containerId: Int,
        inventory: Inventory,
        private val buyer: ServerPlayer,
        private val frameId: java.util.UUID,
        val contents: SimpleContainer = SimpleContainer(9),
    ) : ChestMenu(MenuType.GENERIC_9x1, containerId, inventory, contents, 1) {
        override fun clicked(slot: Int, button: Int, input: ContainerInput, player: Player) {
            if (player !is ServerPlayer || slot !in 0 until contents.containerSize) return
            if (slot !in StoreLadder.QUANTITIES.indices) return
            val quantity = StoreLadder.QUANTITIES[slot]
            player.level().server.execute {
                if (player.containerMenu !== this) return@execute
                val record = StoreFeature.requireService().byFrame(frameId) ?: return@execute
                if (record.stock < quantity) {
                    refresh(player, record)
                    return@execute
                }
                val total = StoreLadder.priceFor(record.pricePerItem, quantity)
                val persistence = eu.mctraveler.MCTraveler.persistence ?: return@execute
                if (!persistence.economy.withdraw(player.uuid, total, Reasons.store(record.frameId))) {
                    player.sendSystemMessage(Paint.error("You don't have enough money — that costs ", Economy.format(total)))
                    refresh(player, record)
                    return@execute
                }
                persistence.economy.deposit(record.owner, total, Reasons.store(record.frameId))
                record.stock -= quantity
                StoreFeature.requireService().save()
                give(player, StoreFeature.recordItem(player, record), quantity)
                player.sendSystemMessage(
                    Paint.store(
                        "Bought ",
                        quantity,
                        "× ",
                        StoreFeature.recordItem(player, record).hoverName,
                        " for ",
                        Economy.format(total),
                    ),
                )
                player.level().server.playerList.getPlayer(record.owner)?.sendSystemMessage(
                    Paint.store(
                        player.gameProfile.name,
                        " bought ",
                        quantity,
                        "× ",
                        StoreFeature.recordItem(player, record).hoverName,
                        " for ",
                        Economy.format(total),
                    ),
                )
                refresh(player, record)
            }
        }

        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

        private fun refresh(player: ServerPlayer, record: StoreRecord) {
            fillBuy(player, this, record)
            broadcastChanges()
        }
    }

    class SellMenu(
        containerId: Int,
        inventory: Inventory,
        private val seller: ServerPlayer,
        private val frameId: java.util.UUID,
        val contents: SimpleContainer = SimpleContainer(9),
    ) : ChestMenu(MenuType.GENERIC_9x1, containerId, inventory, contents, 1) {
        override fun clicked(slot: Int, button: Int, input: ContainerInput, player: Player) {
            if (player !is ServerPlayer || slot !in 0 until contents.containerSize) return
            if (slot !in StoreLadder.QUANTITIES.indices) return
            val quantity = StoreLadder.QUANTITIES[slot]
            player.level().server.execute {
                if (player.containerMenu !== this) return@execute
                val record = StoreFeature.requireService().byFrame(frameId)
                if (record == null || record.kind != StoreKind.BUY) return@execute
                val wanted = StoreFeature.recordItem(player, record)
                val total = StoreLadder.priceFor(record.pricePerItem, quantity)
                val persistence = eu.mctraveler.MCTraveler.persistence ?: return@execute
                if (
                    matchingCount(player, wanted) < quantity ||
                    persistence.economy.balanceOf(record.owner) < total ||
                    record.stock + quantity > record.buyCap
                ) {
                    refresh(player, record)
                    return@execute
                }
                if (!persistence.economy.withdraw(record.owner, total, Reasons.buyOrder(record.frameId))) {
                    refresh(player, record)
                    return@execute
                }
                check(removeMatching(player, wanted, quantity) == quantity)
                persistence.economy.deposit(player.uuid, total, Reasons.buyOrder(record.frameId))
                record.stock += quantity
                StoreFeature.requireService().save()
                player.sendSystemMessage(
                    Paint.store(
                        "Sold ",
                        quantity,
                        "× ",
                        wanted.hoverName,
                        " for ",
                        Economy.format(total),
                    ),
                )
                player.level().server.playerList.getPlayer(record.owner)?.sendSystemMessage(
                    Paint.store(
                        player.gameProfile.name,
                        " sold you ",
                        quantity,
                        "× ",
                        wanted.hoverName,
                        " for ",
                        Economy.format(total),
                    ),
                )
                refresh(player, record)
            }
        }

        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

        private fun refresh(player: ServerPlayer, record: StoreRecord) {
            fillSell(player, this, record)
            broadcastChanges()
        }
    }

    private fun fillSell(player: ServerPlayer, menu: SellMenu, record: StoreRecord) {
        val wanted = StoreFeature.recordItem(player, record)
        val persistence = eu.mctraveler.MCTraveler.persistence
        val balance = persistence?.economy?.balanceOf(record.owner) ?: 0
        for ((slot, quantity) in StoreLadder.QUANTITIES.withIndex()) {
            val total = StoreLadder.priceFor(record.pricePerItem, quantity)
            val reason = when {
                record.stock + quantity > record.buyCap -> "Not buying more"
                balance < total -> "Buyer can't afford this"
                matchingCount(player, wanted) < quantity -> "You don't have enough"
                else -> null
            }
            menu.contents.setItem(
                slot,
                if (reason == null) {
                    wanted.copyWithCount(quantity).apply {
                        set(DataComponents.MAX_STACK_SIZE, 99)
                        set(
                            DataComponents.CUSTOM_NAME,
                            Component.literal(Economy.format(total))
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withItalic(false)),
                        )
                        set(
                            DataComponents.LORE,
                            ItemLore(
                                listOf(
                                    Component.literal("Click to sell $quantity")
                                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)),
                                ),
                            ),
                        )
                    }
                } else {
                    ItemStack(Items.COBWEB, quantity).apply {
                        set(
                            DataComponents.CUSTOM_NAME,
                            Component.literal(reason)
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withItalic(false)),
                        )
                    }
                },
            )
        }
    }

    private fun matchingCount(player: ServerPlayer, wanted: ItemStack): Int =
        player.inventory.getNonEquipmentItems().sumOf { stack ->
            if (ItemStack.isSameItemSameComponents(stack, wanted)) stack.count else 0
        } + player.getItemBySlot(EquipmentSlot.OFFHAND).let { stack ->
            if (ItemStack.isSameItemSameComponents(stack, wanted)) stack.count else 0
        }

    private fun removeMatching(player: ServerPlayer, wanted: ItemStack, amount: Int): Int {
        var remaining = amount
        val stacks = player.inventory.getNonEquipmentItems() +
            player.getItemBySlot(EquipmentSlot.OFFHAND)
        for (stack in stacks) {
            if (remaining == 0) break
            if (!ItemStack.isSameItemSameComponents(stack, wanted)) continue
            val removed = remaining.coerceAtMost(stack.count)
            stack.shrink(removed)
            remaining -= removed
        }
        return amount - remaining
    }

    private fun give(player: ServerPlayer, sold: ItemStack, amount: Int) {
        var remaining = amount
        while (remaining > 0) {
            val count = remaining.coerceAtMost(sold.maxStackSize)
            val stack = sold.copyWithCount(count)
            if (!player.inventory.add(stack)) player.drop(stack, false, Prediction.SERVER_ONLY)
            remaining -= count
        }
    }
}
