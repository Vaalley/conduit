package eu.mctraveler.gametest

import eu.mctraveler.crystal.CrystalEnergy
import eu.mctraveler.crystal.CrystalItem
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Crystal energy as the player experiences it (spec User Stories 23-25):
 * the damage bar every crystal wears, and the recharge that runs on play time.
 *
 * Seam: the packets on the wire ([PacketCapture]) for the damage bar, because
 * "what the viewer sees" is precisely a packet and nothing else; and the running
 * regen loop for the recharge, fast-forwarded by writing the player's own
 * play-time statistic rather than waiting fifteen real minutes.
 */
class CrystalEnergyGameTest {

    @GameTest
    fun aCrystalOnTheWireWearsTheViewersEnergy(helper: GameTestHelper) {
        val viewer = MessageCapturingPlayer.join(helper, "CrystalGauge")
        try {
            viewer.inventory.setItem(0, CrystalItem.of(3))
            for ((energy, expectedDamage) in listOf(3 to 0, 2 to 1, 1 to 2, 0 to 3)) {
                CrystalEnergy.setEnergy(viewer, energy)
                PacketCapture.drain(viewer)
                viewer.containerMenu.sendAllDataToRemote()
                val sent = PacketCapture.drainOf<ClientboundContainerSetContentPacket>(viewer)
                    .flatMap { it.items() }
                    .filter(CrystalItem::isCrystal)
                helper.assertTrue(sent.isNotEmpty(), "the crystal should have been sent at energy $energy")
                for (crystal in sent) {
                    helper.assertValueEqual(
                        crystal.getOrDefault(DataComponents.DAMAGE, 0),
                        expectedDamage,
                        "the damage bar a player with $energy energy sees",
                    )
                }
            }
            helper.succeed()
        } finally {
            viewer.leave()
        }
    }

    @GameTest
    fun theStoredCrystalNeverCarriesDamage(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "CrystalKeeper")
        try {
            owner.inventory.setItem(0, CrystalItem.of(3))
            CrystalEnergy.setEnergy(owner, 0)
            owner.containerMenu.sendAllDataToRemote()
            val stored = owner.inventory.getItem(0)
            helper.assertTrue(
                !stored.has(DataComponents.DAMAGE),
                "the stored crystal must stay undamaged, found ${stored.get(DataComponents.DAMAGE)}",
            )
            helper.succeed()
        } finally {
            owner.leave()
        }
    }

    @GameTest
    fun twoPlayersSeeTheSameCrystalAtTheirOwnEnergy(helper: GameTestHelper) {
        // The point of painting per viewer rather than per item: one crystal,
        // two readings.
        val rich = MessageCapturingPlayer.join(helper, "CrystalRich")
        val poor = MessageCapturingPlayer.join(helper, "CrystalPoor")
        try {
            CrystalEnergy.setEnergy(rich, 3)
            CrystalEnergy.setEnergy(poor, 0)
            for ((viewer, expectedDamage) in listOf(rich to 0, poor to 3)) {
                PacketCapture.drain(viewer)
                viewer.connection.send(
                    ClientboundContainerSetSlotPacket(0, 0, 36, CrystalItem.of(3)),
                )
                val crystals = PacketCapture.drainOf<ClientboundContainerSetSlotPacket>(viewer)
                    .map { it.item }
                    .filter(CrystalItem::isCrystal)
                helper.assertTrue(crystals.isNotEmpty(), "${viewer.gameProfile.name} was sent no crystal")
                helper.assertValueEqual(
                    crystals.single().getOrDefault(DataComponents.DAMAGE, 0),
                    expectedDamage,
                    "the damage ${viewer.gameProfile.name} sees",
                )
            }
            helper.succeed()
        } finally {
            rich.leave()
            poor.leave()
        }
    }

    @GameTest
    fun aCrystalOnTheCursorKeepsItsBar(helper: GameTestHelper) {
        // The cursor and single inventory slots got their own packets in 1.21.4;
        // a crystal picked up in a GUI travels on one of those, not inside a
        // container packet.
        val viewer = MessageCapturingPlayer.join(helper, "CrystalJuggler")
        try {
            CrystalEnergy.setEnergy(viewer, 1)
            PacketCapture.drain(viewer)
            viewer.connection.send(ClientboundSetCursorItemPacket(CrystalItem.of(3)))
            viewer.connection.send(ClientboundSetPlayerInventoryPacket(0, CrystalItem.of(3)))
            // One drain: it empties the channel, so both packets have to come
            // out of the same read.
            val sent = PacketCapture.drain(viewer)
            val cursor = sent.filterIsInstance<ClientboundSetCursorItemPacket>().single()
            helper.assertValueEqual(
                cursor.contents().getOrDefault(DataComponents.DAMAGE, 0),
                2,
                "the damage bar on the cursor",
            )
            val slot = sent.filterIsInstance<ClientboundSetPlayerInventoryPacket>().single()
            helper.assertValueEqual(
                slot.contents().getOrDefault(DataComponents.DAMAGE, 0),
                2,
                "the damage bar on a pushed inventory slot",
            )
            helper.succeed()
        } finally {
            viewer.leave()
        }
    }

    @GameTest
    fun anOrdinaryItemIsSentThroughUntouched(helper: GameTestHelper) {
        val viewer = MessageCapturingPlayer.join(helper, "CrystalBystander")
        try {
            val plain = ItemStack(Items.ECHO_SHARD)
            PacketCapture.drain(viewer)
            viewer.connection.send(ClientboundContainerSetSlotPacket(0, 0, 36, plain))
            val sent = PacketCapture.drainOf<ClientboundContainerSetSlotPacket>(viewer).single()
            helper.assertTrue(
                !sent.item.has(DataComponents.DAMAGE),
                "a plain echo shard must not gain a damage bar",
            )
            helper.succeed()
        } finally {
            viewer.leave()
        }
    }

    // The recharge loop only looks every 20 ticks, and this test waits out two
    // of its passes.
    @GameTest(maxTicks = 200)
    fun energyRechargesOnePointPerFifteenPlayTimeMinutes(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "CrystalPatient")
        try {
            setPlayTime(player, 0)
            CrystalEnergy.setEnergy(player, 1)
            // Spending from full is what arms the clock; setEnergy went through
            // the same path, so the threshold is 15 play-time minutes out.
            helper.assertValueEqual(
                CrystalEnergy.nextRegenAt(store(), player.uuid) ?: -1,
                CrystalEnergy.RECHARGE_TICKS,
                "the armed recharge threshold",
            )
            player.messages.clear()
            // Short of the threshold: nothing yet. The margin is real ticks of
            // slack — the player keeps earning play time while the test waits.
            setPlayTime(player, CrystalEnergy.RECHARGE_TICKS - 500)
            helper.runAfterDelay(21) {
                helper.assertValueEqual(CrystalEnergy.energyOf(player), 1, "energy before the threshold")
                setPlayTime(player, CrystalEnergy.RECHARGE_TICKS)
                helper.runAfterDelay(21) {
                    try {
                        helper.assertValueEqual(CrystalEnergy.energyOf(player), 2, "energy after the threshold")
                        helper.assertTrue(
                            player.messages.map { it.string }
                                .contains("INFO Your energy crystal has recharged one energy"),
                            "the recharge message, got ${player.messages.map { it.string }}",
                        )
                        helper.succeed()
                    } finally {
                        player.leave()
                    }
                }
            }
        } catch (failure: Throwable) {
            player.leave()
            throw failure
        }
    }

    @GameTest(maxTicks = 200)
    fun reachingFullEnergyStopsTheClock(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "CrystalRested")
        try {
            setPlayTime(player, 0)
            CrystalEnergy.setEnergy(player, 2)
            setPlayTime(player, CrystalEnergy.RECHARGE_TICKS)
            helper.runAfterDelay(21) {
                try {
                    helper.assertValueEqual(CrystalEnergy.energyOf(player), 3, "energy after the last point")
                    helper.assertTrue(
                        CrystalEnergy.nextRegenAt(store(), player.uuid) == null,
                        "a full player should have no recharge pending",
                    )
                    helper.succeed()
                } finally {
                    player.leave()
                }
            }
        } catch (failure: Throwable) {
            player.leave()
            throw failure
        }
    }

    private fun store() = checkNotNull(eu.mctraveler.MCTraveler.persistence).players

    /** Fast-forwards the clock the recharge is measured against (no real waiting). */
    private fun setPlayTime(player: ServerPlayer, ticks: Int) {
        player.stats.setValue(player, Stats.CUSTOM.get(Stats.PLAY_TIME), ticks)
    }
}
