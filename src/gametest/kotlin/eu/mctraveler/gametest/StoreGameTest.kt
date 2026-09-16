package eu.mctraveler.gametest

import com.google.gson.JsonParser
import eu.mctraveler.MCTraveler
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
                faceFrame(player, frame)
                player.runCommand("store create 10")
                helper.assertValueEqual(
                    player.messages.last().string,
                    Paint.error("You need $20 to create a store (you have $0)").string,
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
                persistence.economy.set(player.uuid, 25, "test")
                faceFrame(player, frame)
                player.runCommand("store create 10")
                val record = checkNotNull(StoreFeature.requireService().byFrame(frame.uuid))
                helper.assertValueEqual(persistence.economy.balanceOf(player.uuid), 5L, "creation fee balance")
                helper.assertTrue(
                    persistence.ledger.read(player.uuid).any {
                        it.reason == "fee:store-create" && it.delta == -20L
                    },
                    "creation fee was not logged",
                )
                helper.assertTrue(StoreFrames.isMarked(frame), "created frame was not marked")
                helper.assertValueEqual(record.pricePerItem, 10L, "created store price")
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
                persistence.economy.set(player.uuid, 20, "test")
                val record = record(player, frame, stock = 0)
                StoreFeature.requireService().add(record)
                StoreFrames.mark(frame)
                faceFrame(player, frame)
                player.runCommand("store upgrade")
                val upgraded = checkNotNull(StoreFeature.requireService().byFrame(frame.uuid))
                helper.assertValueEqual(upgraded.rows, 6, "upgraded rows")
                helper.assertValueEqual(persistence.economy.balanceOf(player.uuid), 5L, "upgrade fee balance")
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
                persistence.economy.set(owner.uuid, 100, "test")
                persistence.economy.set(seller.uuid, 0, "test")
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
                        helper.assertValueEqual(persistence.economy.balanceOf(seller.uuid), 8L, "seller payment")
                        helper.assertValueEqual(persistence.economy.balanceOf(owner.uuid), 72L, "buyer payment")
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

    private fun faceFrame(player: ServerPlayer, frame: ItemFrame) {
        player.setPos(frame.x, frame.y, frame.z + 3.0)
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(frame.blockPosition()))
    }
}
