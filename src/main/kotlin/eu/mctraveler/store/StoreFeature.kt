package eu.mctraveler.store

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.context.CommandContext
import eu.mctraveler.MCTraveler
import eu.mctraveler.economy.Economy
import eu.mctraveler.economy.Reasons
import eu.mctraveler.region.RegionProtection
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.util.UUID
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

object StoreFeature {
    private val PHASE = Identifier.fromNamespaceAndPath("mctraveler", "store")

    var service: StoreService? = null
        private set

    fun requireService(): StoreService =
        checkNotNull(service) { "the Store service is not started" }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            service = StoreService(server.serverDirectory.resolve("mctraveler").resolve("stores.json"))
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            for (level in server.getAllLevels()) {
                for (entity in level.getAllEntities()) {
                    val frame = entity as? ItemFrame ?: continue
                    if (requireService().byFrame(frame.uuid) != null) StoreFrames.mark(frame)
                }
            }
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> registerCommands(dispatcher) }
        UseEntityCallback.EVENT.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE)
        AttackEntityCallback.EVENT.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE)
        UseEntityCallback.EVENT.register(PHASE) { player, _, hand, entity, _ ->
            if (entity !is ItemFrame) {
                InteractionResult.PASS
            } else {
                val record = requireService().byFrame(entity.uuid)
                if (record == null) {
                    InteractionResult.PASS
                } else if (hand != InteractionHand.MAIN_HAND) {
                    InteractionResult.SUCCESS
                } else {
                    if (player is ServerPlayer) {
                        if (record.owner == player.uuid || RegionsFeature.isAdmin(player)) {
                            StoreMenus.openStock(player, record)
                        } else if (record.kind == StoreKind.BUY) {
                            StoreMenus.openSell(player, record)
                        } else {
                            StoreMenus.openBuy(player, record)
                        }
                    }
                    InteractionResult.SUCCESS
                }
            }
        }
        AttackEntityCallback.EVENT.register(PHASE) { _, _, _, entity, _ ->
            if (entity is ItemFrame && requireService().byFrame(entity.uuid) != null) {
                InteractionResult.FAIL
            } else {
                InteractionResult.PASS
            }
        }
        RegionProtection.exemptMenu {
            it is StoreMenus.StockMenu || it is StoreMenus.BuyMenu || it is StoreMenus.SellMenu
        }
    }

    fun recordItem(player: ServerPlayer, record: StoreRecord): ItemStack =
        StoreCodec.decode(player, record.item).copyWithCount(1)

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("store")
                .executes {
                    reply(it) { Paint.usage("/store create <price>, /store buy <price> [max], /store upgrade, or /store delete") }
                }
                .then(
                    Commands.literal("create")
                        .then(
                            Commands.argument("price", LongArgumentType.longArg(1))
                                .executes { context ->
                                    reply(context) { create(it, price(context), StoreKind.SELL, null) }
                                },
                        ),
                )
                .then(
                    Commands.literal("buy")
                        .then(
                            Commands.argument("price", LongArgumentType.longArg(1))
                                .executes { context ->
                                    reply(context) { create(it, price(context), StoreKind.BUY, null) }
                                }
                                .then(
                                    Commands.argument("max", IntegerArgumentType.integer(1))
                                        .executes { context ->
                                            reply(context) {
                                                create(
                                                    it,
                                                    price(context),
                                                    StoreKind.BUY,
                                                    IntegerArgumentType.getInteger(context, "max"),
                                                )
                                            }
                                        },
                                ),
                        ),
                )
                .then(
                    Commands.literal("upgrade")
                        .executes { context -> reply(context) { upgrade(it) } },
                )
                .then(
                    Commands.literal("delete")
                        .executes { context -> reply(context) { delete(it) } },
                ),
        )
    }

    private fun create(player: ServerPlayer, price: Long, kind: StoreKind, wanted: Int?): Component {
        val frame = StoreFrames.lookedAtFrame(player)
            ?: return Paint.error("You need to look at an item frame")
        val item = frame.item
        if (item.isEmpty) return Paint.error("That item frame is empty")
        if (requireService().byFrame(frame.uuid) != null) {
            return Paint.error("That item frame is already a store")
        }
        if (!RegionProtection.allowsEntityInteract(player, InteractionHand.MAIN_HAND, frame)) {
            return Paint.error("You can't create a store here")
        }
        val persistence = MCTraveler.persistence
            ?: return Paint.error("The economy is unavailable")
        if (!persistence.economy.withdraw(player.uuid, StoreFees.CREATE, Reasons.FEE_STORE_CREATE)) {
            return Paint.error(
                "You need ",
                Economy.format(StoreFees.CREATE),
                " to create a store (you have ",
                Economy.format(persistence.economy.balanceOf(player.uuid)),
                ")",
            )
        }
        StoreFrames.mark(frame)
        requireService().add(
            StoreRecord(
                frameId = frame.uuid,
                owner = player.uuid,
                dimension = player.level().dimension().identifier().toString(),
                pos = frame.blockPosition(),
                item = StoreCodec.encode(player, item),
                pricePerItem = price,
                stock = 0,
                kind = kind,
                wanted = wanted,
            ),
        )
        return if (kind == StoreKind.BUY) {
            Paint.store(
                "Store created (-",
                Economy.format(StoreFees.CREATE),
                ") — buying ",
                item.hoverName,
                " for ",
                Economy.format(price),
                " each",
                wanted?.let { " (up to $it)" } ?: "",
                ".",
            )
        } else {
            Paint.store(
                "Store created (-",
                Economy.format(StoreFees.CREATE),
                ") — selling ",
                item.hoverName,
                " for ",
                Economy.format(price),
                " each. Right-click it to add stock.",
            )
        }
    }

    private fun upgrade(player: ServerPlayer): Component {
        val frame = StoreFrames.lookedAtFrame(player)
            ?: return Paint.error("You need to look at a store")
        val record = requireService().byFrame(frame.uuid)
            ?: return Paint.error("You need to look at a store")
        if (record.owner != player.uuid && !RegionsFeature.isAdmin(player)) {
            return Paint.error("This isn't your store")
        }
        if (record.rows >= 6) return Paint.error("This store is already upgraded")
        val persistence = MCTraveler.persistence
            ?: return Paint.error("The economy is unavailable")
        if (!persistence.economy.withdraw(player.uuid, StoreFees.UPGRADE, Reasons.FEE_STORE_UPGRADE)) {
            return Paint.error(
                "You need ",
                Economy.format(StoreFees.UPGRADE),
                " to upgrade this store (you have ",
                Economy.format(persistence.economy.balanceOf(player.uuid)),
                ")",
            )
        }
        requireService().add(record.copy(rows = 6))
        return Paint.store("Store upgraded to 54 slots (-", Economy.format(StoreFees.UPGRADE), ")")
    }

    private fun delete(player: ServerPlayer): Component {
        val frame = StoreFrames.lookedAtFrame(player)
            ?: return Paint.error("You need to look at a store")
        val record = requireService().byFrame(frame.uuid)
            ?: return Paint.error("You need to look at a store")
        if (record.owner != player.uuid && !RegionsFeature.isAdmin(player)) {
            return Paint.error("This isn't your store")
        }
        val item = recordItem(player, record)
        var remaining = record.stock
        while (remaining > 0) {
            val count = remaining.coerceAtMost(item.maxStackSize)
            Containers.dropItemStack(player.level(), frame.x, frame.y, frame.z, item.copyWithCount(count))
            remaining -= count
        }
        StoreFrames.unmark(frame)
        requireService().remove(frame.uuid)
        return Paint.store("Store deleted, ", record.stock, " items dropped")
    }

    private fun price(context: CommandContext<CommandSourceStack>): Long =
        LongArgumentType.getLong(context, "price")

    private inline fun reply(
        context: CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> Component,
    ): Int {
        val player = context.source.playerOrException
        player.sendSystemMessage(handler(player))
        return Command.SINGLE_SUCCESS
    }
}
