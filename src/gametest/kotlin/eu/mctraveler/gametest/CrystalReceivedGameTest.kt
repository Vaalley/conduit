package eu.mctraveler.gametest

import eu.mctraveler.crystal.CrystalEnergy
import eu.mctraveler.crystal.CrystalItem
import eu.mctraveler.crystal.CrystalRecipes
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket

/** A crystal a player was handed rather than crafted must repaint when their energy moves. */
class CrystalReceivedGameTest {

    @GameTest
    fun theCrystalRecipesAreInEveryRecipeBook(helper: GameTestHelper) {
        val player = TestPlayer.join(helper.level.server, "CrystalBookworm")
        try {
            for (key in CrystalRecipes.KEYS) {
                helper.assertTrue(player.player.recipeBook.contains(key), "$key is not in the recipe book")
            }
            helper.succeed()
        } finally {
            player.disconnect()
        }
    }

    @GameTest(maxTicks = 100)
    fun changingEnergyRepaintsACrystalThePlayerWasGiven(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "CrystalGiftee")
        try {
            // Arrives in the inventory the way a trade, a drop or a chest hands it over.
            player.inventory.add(CrystalItem.of(2))
            player.containerMenu.broadcastChanges()
            PacketCapture.drain(player)

            CrystalEnergy.setEnergy(player, 2)

            val packets = PacketCapture.drain(player)
            val seen = packets.flatMap { packet ->
                when (packet) {
                    is ClientboundContainerSetContentPacket -> packet.items()
                    is ClientboundContainerSetSlotPacket -> listOf(packet.item)
                    is ClientboundSetPlayerInventoryPacket -> listOf(packet.contents())
                    else -> emptyList()
                }
            }.filter(CrystalItem::isCrystal)
            helper.assertTrue(seen.isNotEmpty(), "no repaint was sent when the energy changed, packets: ${packets.map { it.javaClass.simpleName }}")
            helper.assertValueEqual(seen.last().getOrDefault(DataComponents.DAMAGE, 0), 3, "the bar after dropping to 2 energy")
            helper.succeed()
        } finally {
            player.leave()
        }
    }
}
