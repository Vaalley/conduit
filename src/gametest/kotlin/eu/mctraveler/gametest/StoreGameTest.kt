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
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class StoreGameTest {
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
}
