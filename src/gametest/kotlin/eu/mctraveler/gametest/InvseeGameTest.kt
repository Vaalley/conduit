package eu.mctraveler.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput

class InvseeGameTest {

    @GameTest(maxTicks = 120)
    fun invseeCopiesInventoryReadOnlyAndRejectsOfflineTargets(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "InvseeAdmin").also { it.makeAdmin() }
        val target = MessageCapturingPlayer.join(helper, "InvseeTarget")
        target.inventory.setItem(0, ItemStack(Items.DIAMOND))
        target.inventory.setItem(9, ItemStack(Items.COBBLESTONE, 12))
        val originalHotbar = target.inventory.getItem(0).copy()
        val originalMain = target.inventory.getItem(9).copy()

        helper.runAfterDelay(2) {
            admin.runCommand("invsee InvseeTarget")
            val menu = checkNotNull(admin.containerMenu as? ChestMenu) {
                "invsee did not open a chest menu"
            }
            val opened = checkNotNull(PacketCapture.drainOf<ClientboundOpenScreenPacket>(admin).lastOrNull()) {
                "invsee did not send an open-screen packet"
            }
            helper.assertValueEqual(opened.title.string, "Inventory of InvseeTarget", "invsee title")
            helper.assertTrue(menu.getSlot(27).item.`is`(Items.DIAMOND), "hotbar slot was ${menu.getSlot(27).item}")
            helper.assertTrue(menu.getSlot(0).item.count == 12, "main inventory slot was ${menu.getSlot(0).item}")
            menu.clicked(27, 0, ContainerInput.PICKUP, admin)
            helper.assertTrue(
                target.inventory.getItem(0).item == originalHotbar.item &&
                    target.inventory.getItem(0).count == originalHotbar.count,
                "invsee changed target hotbar",
            )
            helper.assertValueEqual(target.inventory.getItem(9).count, originalMain.count, "invsee changed target main inventory")

            target.leave()
            admin.runCommand("invsee InvseeTarget")
            helper.assertTrue(admin.messages.any { it.string.contains("InvseeTarget") && it.string.contains("offline") }, "offline invsee output was ${admin.messages}")
            admin.leave()
            helper.succeed()
        }
    }
}
