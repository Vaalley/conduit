package eu.mctraveler.gametest

import com.google.gson.JsonParser
import net.minecraft.network.chat.Component
import eu.mctraveler.MCTraveler
import eu.mctraveler.economy.Economy
import eu.mctraveler.economy.LedgerEntry
import eu.mctraveler.economy.Reasons
import eu.mctraveler.store.StoreFeature
import eu.mctraveler.store.StoreFrames
import eu.mctraveler.store.StoreLadder
import eu.mctraveler.store.StoreMenus
import eu.mctraveler.store.StoreRecord
import eu.mctraveler.text.Paint
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

class StoreGameTest {
    @GameTest(maxTicks = 100)
    fun createFeeRefusesWithoutFunds(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreNoFee")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        frame.setItem(ItemStack(Items.DIAMOND))
        helper.level.addFreshEntity(frame)
        helper.runAfterDelay(1) {
            try {
                val economy = checkNotNull(MCTraveler.persistence).economy
                economy.set(player.uuid, 0, "test")
                skipFirstStorePrompt(player.uuid)
                faceFrame(player, frame)
                player.runCommand("store create 10")
                helper.assertValueEqual(
                    player.messages.last().string,
                    Paint.error("You need $20.00 to create a store (you have $0.00)").string,
                    "creation fee refusal",
                )
                helper.assertTrue(StoreFeature.requireService().byFrame(frame.uuid) == null, "store was created without funds")
                helper.assertFalse(StoreFrames.isMarked(frame), "frame was marked without funds")
                helper.succeed()
            } finally {
                frame.kill(helper.level)
                player.leave()
            }
        }
    }

    @GameTest(maxTicks = 100)
    fun createFeeIsCharged(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreFee")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        frame.setItem(ItemStack(Items.DIAMOND))
        helper.level.addFreshEntity(frame)
        helper.runAfterDelay(1) {
            try {
                val persistence = checkNotNull(MCTraveler.persistence)
                persistence.economy.set(player.uuid, Economy.dollars(25), "test")
                skipFirstStorePrompt(player.uuid)
                faceFrame(player, frame)
                player.runCommand("store create 0.05")
                val record = checkNotNull(StoreFeature.requireService().byFrame(frame.uuid))
                helper.assertValueEqual(persistence.economy.balanceOf(player.uuid), Economy.dollars(5), "creation fee balance")
                helper.assertTrue(
                    persistence.ledger.read(player.uuid).any {
                        it.reason == "fee:store-create" && it.delta == -Economy.dollars(20)
                    },
                    "creation fee was not logged",
                )
                helper.assertTrue(StoreFrames.isMarked(frame), "created frame was not marked")
                helper.assertValueEqual(record.pricePerItem, 5L, "created store price")
                helper.succeed()
            } finally {
                StoreFeature.requireService().remove(frame.uuid)
                frame.kill(helper.level)
                player.leave()
            }
        }
    }

    @GameTest(maxTicks = 100)
    fun upgradeChargesFeeAndExpandsStock(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreUpgrade")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        helper.level.addFreshEntity(frame)
        helper.runAfterDelay(1) {
            try {
                val persistence = checkNotNull(MCTraveler.persistence)
                persistence.economy.set(player.uuid, Economy.dollars(20), "test")
                val record = record(player, frame, stock = 0)
                StoreFeature.requireService().add(record)
                StoreFrames.mark(frame)
                faceFrame(player, frame)
                player.runCommand("store upgrade")
                val upgraded = checkNotNull(StoreFeature.requireService().byFrame(frame.uuid))
                helper.assertValueEqual(upgraded.rows, 6, "upgraded rows")
                helper.assertValueEqual(
                    persistence.economy.balanceOf(player.uuid),
                    Economy.dollars(5),
                    "upgrade fee balance",
                )
                helper.succeed()
            } finally {
                StoreFeature.requireService().remove(frame.uuid)
                frame.kill(helper.level)
                player.leave()
            }
        }
    }

    @GameTest(maxTicks = 100)
    fun buyOrderPaysSellerAndCollectsItems(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "StoreBuyer")
        val seller = MessageCapturingPlayer.join(helper, "StoreSeller")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        frame.setItem(ItemStack(Items.DIAMOND))
        helper.level.addFreshEntity(frame)
        helper.runAfterDelay(1) {
            try {
                val persistence = checkNotNull(MCTraveler.persistence)
                persistence.economy.set(owner.uuid, Economy.dollars(100), "test")
                persistence.economy.set(seller.uuid, 0, "test")
                skipFirstStorePrompt(owner.uuid)
                faceFrame(owner, frame)
                owner.runCommand("store buy 2 10")
                val record = checkNotNull(StoreFeature.requireService().byFrame(frame.uuid))
                seller.inventory.add(ItemStack(Items.DIAMOND, 10))
                StoreMenus.openSell(seller, record)
                val menu = seller.containerMenu as StoreMenus.SellMenu
                menu.clicked(2, 0, ContainerInput.PICKUP, seller)
                helper.runAfterDelay(1) {
                    try {
                        val updated = checkNotNull(StoreFeature.requireService().byFrame(frame.uuid))
                        helper.assertValueEqual(
                            persistence.economy.balanceOf(seller.uuid),
                            Economy.dollars(8),
                            "seller payment",
                        )
                        helper.assertValueEqual(
                            persistence.economy.balanceOf(owner.uuid),
                            Economy.dollars(72),
                            "buyer payment",
                        )
                        helper.assertValueEqual(updated.stock, 4, "buy-order stock")
                        helper.assertValueEqual(
                            seller.inventory.getNonEquipmentItems()
                                .sumOf { if (it.`is`(Items.DIAMOND)) it.count else 0 },
                            6,
                            "seller remaining items",
                        )
                        helper.succeed()
                    } finally {
                        StoreFeature.requireService().remove(frame.uuid)
                        frame.kill(helper.level)
                        seller.leave()
                        owner.leave()
                    }
                }
            } catch (failure: Throwable) {
                frame.kill(helper.level)
                seller.leave()
                owner.leave()
                throw failure
            }
        }
    }

    // ---- issue #91: the frame item's own price label ----

    @GameTest(maxTicks = 100)
    fun creatingASellStoreLabelsTheItemBuy(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreLabelSell")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        frame.setItem(ItemStack(Items.DIAMOND))
        helper.level.addFreshEntity(frame)
        helper.runAfterDelay(1) {
            try {
                checkNotNull(MCTraveler.persistence).economy.set(player.uuid, Economy.dollars(25), "test")
                skipFirstStorePrompt(player.uuid)
                faceFrame(player, frame)
                player.runCommand("store create 0.10")
                helper.assertValueEqual(
                    checkNotNull(frame.item.get(DataComponents.CUSTOM_NAME)),
                    Paint(Paint.green.bold("Buy"), " ", Paint.white("$0.10")),
                    "a sell store's item label",
                )
                helper.assertTrue(
                    frame.item.get(DataComponents.LORE)?.lines().orEmpty().isEmpty(),
                    "a labeled item carried lore lines",
                )
                helper.succeed()
            } finally {
                StoreFeature.requireService().remove(frame.uuid)
                frame.kill(helper.level)
                player.leave()
            }
        }
    }

    @GameTest(maxTicks = 100)
    fun creatingABuyOrderLabelsTheItemSell(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreLabelBuy")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        frame.setItem(ItemStack(Items.DIAMOND))
        helper.level.addFreshEntity(frame)
        helper.runAfterDelay(1) {
            try {
                checkNotNull(MCTraveler.persistence).economy.set(player.uuid, Economy.dollars(25), "test")
                skipFirstStorePrompt(player.uuid)
                faceFrame(player, frame)
                player.runCommand("store buy 0.10")
                helper.assertValueEqual(
                    checkNotNull(frame.item.get(DataComponents.CUSTOM_NAME)),
                    Paint(Paint.red.bold("Sell"), " ", Paint.white("$0.10")),
                    "a buy order's item label",
                )
                helper.succeed()
            } finally {
                StoreFeature.requireService().remove(frame.uuid)
                frame.kill(helper.level)
                player.leave()
            }
        }
    }

    @GameTest
    fun deletingAStoreClearsTheLabelAndDropsTheFrameItem(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreLabelClear")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        try {
            frame.setItem(ItemStack(Items.DIAMOND))
            helper.level.addFreshEntity(frame)
            val record = record(player, frame, stock = 0)
            StoreFeature.requireService().add(record)
            StoreFrames.mark(frame)
            StoreFrames.label(frame, record)
            faceFrame(player, frame)
            player.runCommand("store delete")
            helper.assertTrue(frame.item.isEmpty, "a deleted store's frame kept its item")
            val dropped = helper.level.getEntities(null, frame.boundingBox.inflate(2.0)) {
                it is net.minecraft.world.entity.item.ItemEntity && it.item.`is`(Items.DIAMOND)
            }.firstOrNull() as? net.minecraft.world.entity.item.ItemEntity
            helper.assertTrue(dropped != null, "a deleted store's frame item was not dropped")
            helper.assertTrue(
                ItemStack.isSameItemSameComponents(dropped!!.item, ItemStack(Items.DIAMOND)),
                "the dropped frame item still carried the price-tag patch, so it won't stack with a plain one",
            )
            helper.succeed()
        } finally {
            frame.kill(helper.level)
            player.leave()
        }
    }

    // ---- first-store confirmation prompt ----

    @GameTest(maxTicks = 100)
    fun aFirstStorePromptsAndConfirmingCreatesIt(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreConfirmA")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        frame.setItem(ItemStack(Items.DIAMOND))
        helper.level.addFreshEntity(frame)
        helper.runAfterDelay(1) {
            try {
                val persistence = checkNotNull(MCTraveler.persistence)
                persistence.economy.set(player.uuid, Economy.dollars(25), "test")
                faceFrame(player, frame)
                player.runCommand("store create 0.10")
                helper.assertTrue(
                    StoreFeature.requireService().byFrame(frame.uuid) == null,
                    "the store was created before it was confirmed",
                )
                helper.assertValueEqual(
                    persistence.economy.balanceOf(player.uuid),
                    Economy.dollars(25),
                    "the fee was charged before confirmation",
                )
                player.runCommand("store confirm")
                val record = checkNotNull(StoreFeature.requireService().byFrame(frame.uuid))
                helper.assertValueEqual(record.pricePerItem, 10L, "the confirmed store's price")
                helper.assertValueEqual(
                    persistence.economy.balanceOf(player.uuid),
                    Economy.dollars(5),
                    "the fee after confirming",
                )
                helper.succeed()
            } finally {
                StoreFeature.requireService().remove(frame.uuid)
                frame.kill(helper.level)
                player.leave()
            }
        }
    }

    @GameTest(maxTicks = 100)
    fun denyingTheFirstStorePromptCancelsIt(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreConfirmB")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        frame.setItem(ItemStack(Items.DIAMOND))
        helper.level.addFreshEntity(frame)
        helper.runAfterDelay(1) {
            try {
                val persistence = checkNotNull(MCTraveler.persistence)
                persistence.economy.set(player.uuid, Economy.dollars(25), "test")
                faceFrame(player, frame)
                player.runCommand("store create 0.10")
                player.runCommand("store deny")
                helper.assertTrue(
                    StoreFeature.requireService().byFrame(frame.uuid) == null,
                    "a denied store was created anyway",
                )
                helper.assertValueEqual(
                    persistence.economy.balanceOf(player.uuid),
                    Economy.dollars(25),
                    "a denied store still charged the fee",
                )
                player.runCommand("store confirm")
                helper.assertValueEqual(
                    player.messages.last(),
                    Paint.error("You have nothing to confirm"),
                    "confirming after a deny",
                )
                helper.succeed()
            } finally {
                StoreFeature.requireService().remove(frame.uuid)
                frame.kill(helper.level)
                player.leave()
            }
        }
    }

    // ---- issue #91: a full inventory refuses the buy ----

    @GameTest(maxTicks = 100)
    fun aFullInventoryRefusesToBuy(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "StoreFullOwner")
        val buyer = MessageCapturingPlayer.join(helper, "StoreFullBuyer")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        frame.setItem(ItemStack(Items.DIAMOND))
        helper.level.addFreshEntity(frame)
        try {
            // A tick after joining, not before: a brand-new account's join
            // bonus lands a tick late, and setting the balance before that
            // would only have the bonus overwrite it right back.
            helper.runAfterDelay(1) {
                try {
                    val persistence = checkNotNull(MCTraveler.persistence)
                    persistence.economy.set(buyer.uuid, Economy.dollars(100), "test")
                    val record = record(owner, frame, stock = 20)
                    StoreFeature.requireService().add(record)
                    val items = buyer.inventory.getNonEquipmentItems()
                    for (i in items.indices) items[i] = ItemStack(Items.COBBLESTONE, 64)
                    StoreMenus.openBuy(buyer, record)
                    val menu = buyer.containerMenu as StoreMenus.BuyMenu
                    menu.clicked(0, 0, ContainerInput.PICKUP, buyer)
                    helper.runAfterDelay(1) {
                        try {
                            helper.assertValueEqual(
                                buyer.saying(Paint.error("Your inventory is full").string),
                                Paint.error("Your inventory is full"),
                                "full-inventory refusal",
                            )
                            helper.assertValueEqual(
                                persistence.economy.balanceOf(buyer.uuid),
                                Economy.dollars(100),
                                "buyer was charged despite a full inventory",
                            )
                            val unchanged = checkNotNull(StoreFeature.requireService().byFrame(frame.uuid))
                            helper.assertValueEqual(unchanged.stock, 20, "stock changed despite a full inventory")
                            helper.succeed()
                        } finally {
                            StoreFeature.requireService().remove(frame.uuid)
                            frame.kill(helper.level)
                            buyer.leave()
                            owner.leave()
                        }
                    }
                } catch (failure: Throwable) {
                    frame.kill(helper.level)
                    buyer.leave()
                    owner.leave()
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            frame.kill(helper.level)
            buyer.leave()
            owner.leave()
            throw failure
        }
    }

    @GameTest
    fun createLocksRealFrame(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreCreate")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        try {
            frame.setItem(ItemStack(Items.DIAMOND))
            helper.level.addFreshEntity(frame)
            StoreFrames.mark(frame)
            helper.assertTrue(frame.isInvulnerable, "store frame was not invulnerable")
            helper.assertTrue(StoreFrames.isMarked(frame), "store tag was absent")
            StoreFrames.unmark(frame)
            helper.assertFalse(StoreFrames.isMarked(frame), "store tag remained after unlock")
            helper.succeed()
        } finally {
            frame.kill(helper.level)
            player.leave()
        }
    }

    @GameTest
    fun commandsRefuseMissingFrame(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreNoFrame")
        try {
            player.runCommand("store create 10")
            helper.assertValueEqual(player.messages.last(), Paint.error("You need to look at an item frame"), "no-frame refusal")
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun stockMenuIsThreeRows(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreStock")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        try {
            helper.level.addFreshEntity(frame)
            val record = record(player, frame, stock = 20)
            StoreFeature.requireService().add(record)
            StoreMenus.openStock(player, record)
            helper.assertTrue(player.containerMenu is ChestMenu, "stock menu did not open")
            helper.assertValueEqual((player.containerMenu as ChestMenu).rowCount, 3, "stock rows")
            helper.succeed()
        } finally {
            StoreFeature.requireService().remove(frame.uuid)
            frame.kill(helper.level)
            player.leave()
        }
    }

    @GameTest
    fun buyMenuHasLadderAndOutOfStock(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreBuy")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        try {
            helper.level.addFreshEntity(frame)
            val record = record(player, frame, stock = 4)
            StoreFeature.requireService().add(record)
            StoreMenus.openBuy(player, record)
            val menu = player.containerMenu as StoreMenus.BuyMenu
            helper.assertValueEqual(menu.getContainer().getItem(0).count, 1, "one-item option")
            helper.assertValueEqual(menu.getContainer().getItem(6).item, Items.COBWEB, "out-of-stock icon")
            helper.assertValueEqual(StoreLadder.QUANTITIES.size, 9, "quantity ladder size")
            helper.succeed()
        } finally {
            StoreFeature.requireService().remove(frame.uuid)
            frame.kill(helper.level)
            player.leave()
        }
    }

    @GameTest
    fun insufficientBalanceIsUnchanged(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreFunds")
        try {
            val economy = checkNotNull(MCTraveler.persistence).economy
            helper.assertFalse(economy.withdraw(player.uuid, 1, "test"), "empty balance allowed withdrawal")
            helper.assertValueEqual(economy.balanceOf(player.uuid), 0L, "empty balance changed")
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun deleteUnlocksAndKeepsFrameItem(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreDelete")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        try {
            frame.setItem(ItemStack(Items.DIAMOND))
            helper.level.addFreshEntity(frame)
            StoreFrames.mark(frame)
            StoreFrames.unmark(frame)
            helper.assertTrue(frame.item.`is`(Items.DIAMOND), "deleting a store lost its item")
            helper.assertFalse(frame.isInvulnerable, "deleted frame remained invulnerable")
            helper.succeed()
        } finally {
            frame.kill(helper.level)
            player.leave()
        }
    }

    @GameTest
    fun griefAttackCannotBreakLockedFrame(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "StoreGrief")
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        try {
            helper.level.addFreshEntity(frame)
            StoreFrames.mark(frame)
            helper.assertTrue(frame.isAlive, "locked frame was not alive")
            helper.assertTrue(frame.isInvulnerable, "locked frame was vulnerable")
            helper.succeed()
        } finally {
            frame.kill(helper.level)
            player.leave()
        }
    }

    // ---- /store admin ----

    @GameTest
    fun adminModeIsAbsentFromANonAdminsCommandTree(helper: GameTestHelper) {
        val server = helper.level.server
        val stranger = MessageCapturingPlayer.join(helper, "StoreAdminTreeA")
        val admin = MessageCapturingPlayer.join(helper, "StoreAdminTreeB")
        admin.makeAdmin()
        try {
            val node = server.commands.dispatcher.root.getChild("store")?.getChild("admin")
                ?: throw helper.assertionException("the /store admin command is not registered at all")
            helper.assertFalse(
                node.requirement.test(stranger.createCommandSourceStack()),
                "/store admin is visible to a non-admin's command tree",
            )
            helper.assertTrue(
                node.requirement.test(admin.createCommandSourceStack()),
                "/store admin is hidden from an admin's command tree",
            )
            helper.succeed()
        } finally {
            stranger.leave()
            admin.leave()
        }
    }

    @GameTest
    fun adminModeIsOffByDefaultAndTogglesWithAMessage(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "StoreAdminToggle")
        admin.makeAdmin()
        try {
            admin.runCommand("store admin")
            helper.assertValueEqual(
                admin.messages.last(),
                Paint.info("Store admin mode is now on — you can view and modify any store"),
                "turning admin mode on",
            )
            admin.runCommand("store admin")
            helper.assertValueEqual(
                admin.messages.last(),
                Paint.info("Store admin mode is now off — you interact with stores like anyone else"),
                "turning admin mode back off",
            )
            helper.succeed()
        } finally {
            admin.leave()
        }
    }

    @GameTest
    fun anAdminSeesAStoreAsAVisitorUntilAdminModeIsOn(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "StoreAdminOwnerA")
        val admin = MessageCapturingPlayer.join(helper, "StoreAdminViewA")
        admin.makeAdmin()
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        try {
            helper.level.addFreshEntity(frame)
            val record = record(owner, frame, stock = 4)
            StoreFeature.requireService().add(record)
            StoreFrames.mark(frame)

            interactWith(admin, frame)
            helper.assertTrue(admin.containerMenu is StoreMenus.BuyMenu, "an admin with admin mode off saw the stock menu")
            admin.closeContainer()

            admin.runCommand("store admin")
            interactWith(admin, frame)
            helper.assertTrue(admin.containerMenu is StoreMenus.StockMenu, "an admin with admin mode on did not see the stock menu")
            helper.succeed()
        } finally {
            StoreFeature.requireService().remove(frame.uuid)
            frame.kill(helper.level)
            owner.leave()
            admin.leave()
        }
    }

    /** Simulates [player] right-clicking [frame] with an empty main hand — the store's own open trigger. */
    private fun interactWith(player: ServerPlayer, frame: ItemFrame) {
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker().interact(
            player,
            player.level(),
            net.minecraft.world.InteractionHand.MAIN_HAND,
            frame,
            net.minecraft.world.phys.EntityHitResult(frame),
        )
    }

    @GameTest
    fun adminModeGatesDeletingAnotherOwnersStore(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "StoreAdminOwnerB")
        val admin = MessageCapturingPlayer.join(helper, "StoreAdminViewB")
        admin.makeAdmin()
        val frame = ItemFrame(helper.level, helper.absolutePos(BlockPos(2, 2, 2)), Direction.SOUTH)
        try {
            frame.setItem(ItemStack(Items.DIAMOND))
            helper.level.addFreshEntity(frame)
            val record = record(owner, frame, stock = 0)
            StoreFeature.requireService().add(record)
            StoreFrames.mark(frame)
            faceFrame(admin, frame)

            admin.runCommand("store delete")
            helper.assertValueEqual(admin.messages.last(), Paint.error("This isn't your store"), "delete with admin mode off")
            helper.assertTrue(StoreFeature.requireService().byFrame(frame.uuid) != null, "the store was deleted with admin mode off")

            admin.runCommand("store admin")
            admin.runCommand("store delete")
            helper.assertTrue(StoreFeature.requireService().byFrame(frame.uuid) == null, "admin mode did not allow the delete")
            helper.succeed()
        } finally {
            StoreFeature.requireService().remove(frame.uuid)
            frame.kill(helper.level)
            owner.leave()
            admin.leave()
        }
    }

    private fun record(player: ServerPlayer, frame: ItemFrame, stock: Int): StoreRecord =
        StoreRecord(
            frame.uuid,
            player.uuid,
            player.level().dimension().identifier().toString(),
            frame.blockPosition(),
            JsonParser.parseString("""{"id":"minecraft:diamond","count":1}"""),
            10,
            stock,
        )

    /** Seeds [uuid]'s ledger so `/store create`/`/store buy` skips the first-timer confirmation prompt. */
    private fun skipFirstStorePrompt(uuid: UUID) {
        checkNotNull(MCTraveler.persistence).ledger.append(LedgerEntry(0L, uuid, 0L, 0L, Reasons.FEE_STORE_CREATE))
    }

    private fun faceFrame(player: ServerPlayer, frame: ItemFrame) {
        player.setPos(frame.x, frame.y, frame.z + 3.0)
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(frame.blockPosition()))
    }

    /**
     * The message reading exactly [text]. A deferred assertion like the buy
     * menu's runs a tick after the click, and the gametest framework
     * broadcasts every finished test's result to every player in the meantime,
     * so the refusal is rarely the last thing this player heard.
     */
    private fun MessageCapturingPlayer.saying(text: String): Component =
        checkNotNull(messages.firstOrNull { it.string == text }) {
            "$name heard no message saying \"$text\""
        }
}
