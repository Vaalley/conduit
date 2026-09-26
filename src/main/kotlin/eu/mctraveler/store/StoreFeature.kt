package eu.mctraveler.store

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
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
                    val record = requireService().byFrame(frame.uuid) ?: continue
                    StoreFrames.mark(frame)
                    StoreFrames.label(frame, record)
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
                            Commands.argument("price", StringArgumentType.word())
                                .executes { context ->
                                    reply(context) {
                                        val value = price(context)
                                            ?: return@reply Paint.error("Amount must be a number like 12.50")
                                        create(it, value, StoreKind.SELL, null)
                                    }
                                },
                        ),
                )
                .then(
                    Commands.literal("buy")
                        .then(
                            Commands.argument("price", StringArgumentType.word())
                                .executes { context ->
                                    reply(context) {
                                        val value = price(context)
                                            ?: return@reply Paint.error("Amount must be a number like 12.50")
                                        create(it, value, StoreKind.BUY, null)
                                    }
                                }
                                .then(
                                    Commands.argument("max", IntegerArgumentType.integer(1))
                                        .executes { context ->
                                            reply(context) {
                                                create(
                                                    it,
                                                    price(context)
                                                        ?: return@reply Paint.error("Amount must be a number like 12.50"),
                                                    StoreKind.BUY,
                                                    IntegerArgumentType.getInteger(context, "max"),
                                                )
                                            }
                                        },
                                ),
                        ),
                )
                .then(
                    Commands.literal("confirm")
                        .executes { context -> reply(context) { confirm(it) } },
                )
                .then(
                    Commands.literal("deny")
                        .executes { context -> reply(context) { deny(it) } },
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

    /** A `/store create`/`/store buy` a first-timer hasn't yet accepted the fee for. */
    private data class PendingCreate(val price: Long, val kind: StoreKind, val wanted: Int?, val frameId: UUID, val tick: Int)

    /** How long a confirmation prompt stays good for — a full minute, generous for reading it. */
    private const val CONFIRM_WINDOW_TICKS = 1200

    private val pendingCreate = HashMap<UUID, PendingCreate>()

    private fun create(player: ServerPlayer, price: Long, kind: StoreKind, wanted: Int?): Component {
        val frame = StoreFrames.lookedAtFrame(player)
            ?: return Paint.error("You need to look at an item frame")
        if (frame.item.isEmpty) return Paint.error("That item frame is empty")
        if (requireService().byFrame(frame.uuid) != null) {
            return Paint.error("That item frame is already a store")
        }
        if (!RegionProtection.allowsEntityInteract(player, InteractionHand.MAIN_HAND, frame)) {
            return Paint.error("You can't create a store here")
        }
        // A player's very first store asks first — every later one goes straight through.
        if (isFirstStore(player.uuid)) {
            pendingCreate[player.uuid] = PendingCreate(price, kind, wanted, frame.uuid, player.level().server.tickCount)
            return confirmPrompt()
        }
        return chargeAndCreate(player, frame, price, kind, wanted)
    }

    private fun confirm(player: ServerPlayer): Component {
        val pending = pendingCreate.remove(player.uuid)
            ?: return Paint.error("You have nothing to confirm")
        if (player.level().server.tickCount - pending.tick > CONFIRM_WINDOW_TICKS) {
            return Paint.error("That confirmation expired — try again")
        }
        val frame = player.level().getEntity(pending.frameId) as? ItemFrame
            ?: return Paint.error("That item frame is gone")
        if (!RegionProtection.allowsEntityInteract(player, InteractionHand.MAIN_HAND, frame)) {
            return Paint.error("You can't create a store here")
        }
        return chargeAndCreate(player, frame, pending.price, pending.kind, pending.wanted)
    }

    private fun deny(player: ServerPlayer): Component {
        pendingCreate.remove(player.uuid)
        return Paint.info("Store creation cancelled")
    }

    /** Whether [uuid] has never had a store-creation fee charged — the ledger is the record of it. */
    private fun isFirstStore(uuid: UUID): Boolean =
        MCTraveler.persistence?.ledger?.read(uuid)?.none { it.reason == Reasons.FEE_STORE_CREATE } ?: false

    private fun confirmPrompt(): Component = Paint(
        Paint.gray("Creating your first store costs ", Economy.format(StoreFees.CREATE), ". Continue?"),
        "\n",
        Paint.green.bold.runs("/store confirm")("[Accept]"),
        "  ",
        Paint.red.bold.runs("/store deny")("[Deny]"),
    )

    /** The fee-charging, record-building half of store creation, shared by a direct create and a confirmed one. */
    private fun chargeAndCreate(player: ServerPlayer, frame: ItemFrame, price: Long, kind: StoreKind, wanted: Int?): Component {
        val item = frame.item
        if (item.isEmpty) return Paint.error("That item frame is empty")
        if (requireService().byFrame(frame.uuid) != null) {
            return Paint.error("That item frame is already a store")
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
        val record = StoreRecord(
            frameId = frame.uuid,
            owner = player.uuid,
            dimension = player.level().dimension().identifier().toString(),
            pos = frame.blockPosition(),
            item = StoreCodec.encode(player, item),
            pricePerItem = price,
            stock = 0,
            kind = kind,
            wanted = wanted,
        )
        StoreFrames.mark(frame)
        StoreFrames.label(frame, record)
        requireService().add(record)
        return Paint.success("Store created! ", Paint.red("-", Economy.format(StoreFees.CREATE)))
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
        StoreFrames.clearLabel(frame)
        // The frame's own displayed item is the store's sample, not part of the
        // stock — deleting the store should still hand it back rather than
        // leave it sitting in what is now a plain, unlocked item frame.
        val displayed = frame.item
        if (!displayed.isEmpty) {
            Containers.dropItemStack(player.level(), frame.x, frame.y, frame.z, displayed)
            frame.setItem(ItemStack.EMPTY, false)
        }
        requireService().remove(frame.uuid)
        return Paint.store("Store deleted, ", record.stock, " items dropped")
    }

    private fun price(context: CommandContext<CommandSourceStack>): Long? =
        Economy.parseAmount(StringArgumentType.getString(context, "price"), 1)

    private inline fun reply(
        context: CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> Component,
    ): Int {
        val player = context.source.playerOrException
        player.sendSystemMessage(handler(player))
        return Command.SINGLE_SUCCESS
    }
}
