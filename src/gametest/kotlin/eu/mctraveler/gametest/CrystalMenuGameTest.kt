package eu.mctraveler.gametest

import eu.mctraveler.crystal.CrystalEnergy
import eu.mctraveler.crystal.CrystalFeature
import eu.mctraveler.crystal.CrystalItem
import eu.mctraveler.crystal.CrystalMenu
import eu.mctraveler.crystal.CrystalRequests
import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.embassy.EmbassyOrigins
import eu.mctraveler.region.RegionProtection
import eu.mctraveler.text.Paint
import eu.mctraveler.worlds.DimensionRole
import eu.mctraveler.worlds.WorldsFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * The crystal's menu as a player meets it (spec User Stories 26-36): the
 * right-click that opens it, the layout it opens with, both refusals, each of
 * the six destinations, and the teleport request the head GUI sends.
 *
 * Seams: the real item-use path ([net.minecraft.server.level.ServerPlayerGameMode.useItem]),
 * so the region exemption of deviation 13 is exercised rather than assumed; the
 * live [net.minecraft.world.inventory.AbstractContainerMenu.clicked], because
 * that is the only thing a clicking client can reach; and the raw command packet
 * for the accept command, which by design no dispatcher will ever see.
 *
 * Every acting click is answered a tick later — the destination runs on the
 * server's task queue rather than inside the click (see
 * [CrystalMenu.CrystalChestMenu.clicked]) — so assertions hang off [afterClick].
 */
class CrystalMenuGameTest {

    // ---- opening, and the two refusals ----

    @GameTest
    fun rightClickingACrystalOpensTheDestinationMenu(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCOpen")
        try {
            PacketCapture.drain(player)
            player.usesCrystal(tier = 3)

            val menu = CrystalMenu.openMenuOf(player)
            helper.assertTrue(menu != null, "right-clicking a crystal opened no menu")
            helper.assertValueEqual(menu!!.kind, CrystalMenu.Kind.DESTINATIONS, "the menu kind")
            // The title is the one thing only the packet carries.
            val opened = PacketCapture.drainOf<ClientboundOpenScreenPacket>(player).single()
            helper.assertValueEqual(opened.title.string, CrystalMenu.TITLE, "the menu title")
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun rightClickingABlockWithACrystalOpensTheMenuToo(helper: GameTestHelper) {
        // The other half of story 26's "air or block": a different vanilla path
        // and a different Fabric event from the air right-click above.
        val player = MessageCapturingPlayer.join(helper, "TCOnBlock")
        try {
            val floor = BlockPos(1, 1, 1)
            helper.setBlock(floor, Blocks.STONE)
            player.standAt(helper, 1.0, 2.0, 2.0)

            helper.assertTrue(
                player.usesCrystalOn(helper, floor) != InteractionResult.PASS,
                "the crystal's block use was left to vanilla",
            )
            helper.assertTrue(
                CrystalMenu.openMenuOf(player) != null,
                "right-clicking a block with a crystal opened no menu",
            )
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun aCrystalAimedAtAChestOpensTheMenuNotTheChest(helper: GameTestHelper) {
        // Nucleus cancelled Bukkit's PlayerInteractEvent before the chest could
        // open, so the crystal always won the click. Vanilla runs a block's own
        // right-click ahead of the item's, so this only holds because the hook
        // is UseBlockCallback — which is ahead of both.
        val player = MessageCapturingPlayer.join(helper, "TCOnChest")
        try {
            val chest = BlockPos(1, 1, 1)
            helper.setBlock(chest, Blocks.CHEST)
            helper.level.getBlockEntity(helper.absolutePos(chest))!!
                .let { it as net.minecraft.world.level.block.entity.ChestBlockEntity }
                .setItem(0, ItemStack(Items.DIAMOND))
            player.standAt(helper, 1.0, 1.0, 2.0)
            CrystalEnergy.setEnergy(player, 5)
            player.usesCrystalOn(helper, chest)

            val menu = CrystalMenu.openMenuOf(player)
            helper.assertTrue(menu != null, "the chest won the click against a crystal")
            helper.assertValueEqual(menu!!.kind, CrystalMenu.Kind.DESTINATIONS, "the menu the crystal opened")
            // The chest's own contents are the tell: a chest that opened would
            // have put a diamond in the player's window.
            helper.assertTrue(
                menu.contents().none { it.`is`(Items.DIAMOND) },
                "the chest opened as well as the menu",
            )
            helper.assertValueEqual(CrystalEnergy.energyOf(player), 5, "energy for merely opening the menu")
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun aCrystalInTheOffHandOpensTheMenuToo(helper: GameTestHelper) {
        // Vanilla asks each hand in turn and our hook reads the hand the click
        // came in on — Nucleus's `e.item`, whose interact event fired per hand.
        val player = MessageCapturingPlayer.join(helper, "TCOffHand")
        try {
            val floor = BlockPos(1, 1, 1)
            helper.setBlock(floor, Blocks.STONE)
            player.standAt(helper, 1.0, 2.0, 2.0)
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
            player.setItemInHand(InteractionHand.OFF_HAND, CrystalItem.of(3))

            val absolute = helper.absolutePos(floor)
            val hit = BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false)
            player.gameMode.useItemOn(player, player.level(), player.offhandItem, InteractionHand.OFF_HAND, hit)

            helper.assertTrue(
                CrystalMenu.openMenuOf(player) != null,
                "a crystal in the off hand opened no menu",
            )
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun theMenuIsLaidOutExactlyAsNucleusLaidItOut(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCLayout")
        try {
            CrystalEnergy.setEnergy(player, 3)
            player.usesCrystal(tier = 3)
            val slots = CrystalMenu.openMenuOf(player)!!.contents()

            helper.assertValueEqual(slots.size, 27, "the menu size")
            for (slot in ((0..8) + (18..26)).filter { it != 4 }) {
                helper.assertTrue(
                    slots[slot].`is`(Items.STAINED_GLASS_PANE.black()),
                    "slot $slot should be a black pane, found ${slots[slot]}",
                )
            }
            helper.assertTrue(slots[4].`is`(Items.AMETHYST_SHARD), "slot 4 should show energy")
            helper.assertTrue(
                slots[4].get(DataComponents.CUSTOM_NAME)?.string == "Energy: 3/5",
                "the energy info item",
            )
            for (slot in listOf(9, 10, 17)) {
                helper.assertTrue(
                    slots[slot].`is`(Items.STAINED_GLASS_PANE.blue()),
                    "slot $slot should be a blue pane, found ${slots[slot]}",
                )
            }
            val expected = listOf(
                11 to Triple(Items.BED.blue(), "Bed", listOf("Go back to your place of rest")),
                12 to Triple(Items.SPAWNER, "Spawn 1", listOf("Head to spawn town")),
                13 to Triple(Items.SPAWNER, "Spawn 2", listOf("Head to the remote spawn")),
                14 to Triple(
                    Items.PLAYER_HEAD,
                    "Player",
                    listOf("Request to teleport to a player", "costs one energy when they accept"),
                ),
                15 to Triple(Items.SPYGLASS, "Embassy", listOf("Teleport to the embassy world")),
                16 to Triple(Items.GRASS_BLOCK, "Wilderness", listOf("Coming soon")),
            )
            for ((slot, want) in expected) {
                val (item, name, lore) = want
                val stack = slots[slot]
                helper.assertTrue(stack.`is`(item), "slot $slot should be $item, found $stack")
                // Nucleus set Bukkit's display name, which is custom_name — the
                // component vanilla renders in italic, as it renders any renamed
                // item. item_name would read upright and would not match.
                helper.assertValueEqual(
                    stack.get(DataComponents.CUSTOM_NAME)?.string ?: "<unnamed>",
                    name,
                    "the name on slot $slot",
                )
                helper.assertValueEqual(
                    stack.get(DataComponents.LORE)?.lines().orEmpty().map { it.string },
                    lore,
                    "the lore on slot $slot",
                )
                helper.assertTrue(
                    stack.get(DataComponents.TOOLTIP_DISPLAY)?.shows(DataComponents.PROFILE) == false,
                    "slot $slot should hide its additional tooltip",
                )
            }
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun aSecondRightClickWhileTheMenuIsOpenIsRefused(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCTwice")
        try {
            player.usesCrystal(tier = 3)
            player.messages.clear()
            player.usesCrystal(tier = 3)

            helper.assertOnlyMessage(
                player,
                Paint.error("You are already in a teleportation crystal."),
                "the already-open refusal",
            )
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun aTierNeedsTheEnergyItsCapacityImplies(helper: GameTestHelper) {
        // Story 27: energy <= 3 - tier is refused. A tier-1 crystal needs a
        // full pool; a tier-3 still works on the last point.
        val player = MessageCapturingPlayer.join(helper, "TCEnergyGate")
        try {
            for ((tier, energy, opens) in TIER_ENERGY_CASES) {
                CrystalEnergy.setEnergy(player, energy)
                player.messages.clear()
                player.usesCrystal(tier)
                val menu = CrystalMenu.openMenuOf(player)
                helper.assertTrue(
                    (menu != null) == opens,
                    "tier $tier at $energy energy should ${if (opens) "open" else "refuse"}",
                )
                if (opens) {
                    player.closeContainer()
                } else {
                    helper.assertOnlyMessage(
                        player,
                        Paint.error("You have no energy, please wait for a recharge"),
                        "the no-energy refusal for tier $tier at $energy",
                    )
                }
            }
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    // ---- deviation 13: the crystal works on someone else's land ----

    @GameTest
    fun theMenuOpensInsideARegionThePlayerCannotTouch(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "TCLandlord")
        val guest = MessageCapturingPlayer.join(helper, "TCGuest")
        try {
            createRegion(helper, owner, 0.0 to 0.0, 4.0 to 4.0)
            guest.standAt(helper, 2.0, 1.0, 2.0)
            // Precondition: this really is ground the guest is refused on.
            guest.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.STONE))
            guest.gameMode.useItem(guest, guest.level(), guest.mainHandItem, InteractionHand.MAIN_HAND)
            helper.assertTrue(
                guest.wasRefusedBy("TCLandlord's Place"),
                "the guest was not refused an ordinary item use, so this proves nothing",
            )

            guest.messages.clear()
            guest.usesCrystal(tier = 3)
            helper.assertTrue(
                CrystalMenu.openMenuOf(guest) != null,
                "the crystal was refused inside a foreign region (deviation 13)",
            )
            helper.assertFalse(
                guest.wasRefusedBy("TCLandlord's Place"),
                "the crystal drew a protection refusal, got ${guest.messages.map { it.string }}",
            )

            // The block path is guarded by a different rule (allowsBlockChange,
            // which is what refuses building) and so needs its own exemption.
            guest.closeContainer()
            val floor = BlockPos(2, 1, 2)
            helper.setBlock(floor, Blocks.STONE)
            guest.messages.clear()
            guest.usesCrystalOn(helper, floor)
            helper.assertTrue(
                CrystalMenu.openMenuOf(guest) != null,
                "the crystal was refused on a block inside a foreign region (deviation 13)",
            )
            helper.assertFalse(
                guest.wasRefusedBy("TCLandlord's Place"),
                "the crystal-on-block drew a protection refusal, got ${guest.messages.map { it.string }}",
            )
            helper.succeed()
        } finally {
            owner.leave()
            guest.leave()
        }
    }

    // ---- deviation 16: the menu is the mod's, not the region's ----

    @GameTest
    fun aCrystalMenuCapturesNoContainerSession(helper: GameTestHelper) {
        // The reachable half of deviation 16. The click mixin's exemption is
        // belt-and-braces — CrystalChestMenu.clicked overrides the method it
        // injects into and never delegates — but the session mixin's is live:
        // a menu the mod drew stands in no region, so no region may be captured
        // as the one governing it.
        val owner = MessageCapturingPlayer.join(helper, "TCSessionA")
        val guest = MessageCapturingPlayer.join(helper, "TCSessionB")
        try {
            createRegion(helper, owner, 0.0 to 0.0, 4.0 to 4.0)
            guest.standAt(helper, 2.0, 1.0, 2.0)
            guest.usesCrystal(tier = 3)
            guest.messages.clear()

            helper.assertTrue(
                RegionProtection.allowsContainerUse(guest),
                "opening a crystal menu captured the region it was opened in",
            )
            helper.assertTrue(
                guest.spokenMessages().isEmpty(),
                "asking about the container session drew ${guest.spokenMessages().map { it.string }}",
            )
            helper.succeed()
        } finally {
            owner.leave()
            guest.leave()
        }
    }

    @GameTest
    fun aMenuOpenedOnForeignLandStillAcceptsItsClicks(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "TCHostA")
        val guest = MessageCapturingPlayer.join(helper, "TCHostB")
        createRegion(helper, owner, 0.0 to 0.0, 4.0 to 4.0)
        guest.standAt(helper, 2.0, 1.0, 2.0)
        guest.usesCrystal(tier = 3)
        guest.messages.clear()

        helper.afterClick(guest, WILDERNESS_SLOT, owner, guest) {
            // Container protection would have swallowed the click before the
            // menu ever saw it; Wilderness's own refusal proves it got through.
            helper.assertOnlyMessage(
                guest,
                Paint.error("Sorry, this feature is not available yet"),
                "the Wilderness refusal from inside a foreign region",
            )
        }
    }

    // ---- clicks never move anything ----

    @GameTest
    fun noClickInTheMenuMovesAnything(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCSticky")
        try {
            player.usesCrystal(tier = 3)
            // Slot 1, not 0: the crystal is held, and holding it is slot 0.
            player.inventory.setItem(1, ItemStack(Items.DIAMOND, 5))
            val menu = CrystalMenu.openMenuOf(player)!!

            // Only slots that do not act, so this test is about movement alone
            // (the acting slots have their own tests). Shift-clicking a pane is
            // the one players actually try; the last two are the player's own
            // half of the window and the click that lands outside it.
            val inert = listOf(0, 9, 17, 26, menu.contents().size + 4, -999)
            for (input in listOf(
                ContainerInput.PICKUP,
                ContainerInput.QUICK_MOVE,
                ContainerInput.THROW,
                ContainerInput.SWAP,
                ContainerInput.PICKUP_ALL,
            )) {
                for (slot in inert) menu.clicked(slot, 0, input, player)
            }
            helper.assertTrue(
                menu.contents()[0].`is`(Items.STAINED_GLASS_PANE.black()),
                "a pane left the menu",
            )
            helper.assertTrue(
                CrystalMenu.openMenuOf(player) != null,
                "an inert click closed the menu",
            )
            // Shift-clicking out of the player's own half must not push
            // anything into the menu either.
            helper.assertValueEqual(
                player.inventory.getItem(1).count,
                5,
                "the diamonds the player brought in",
            )
            helper.assertTrue(menu.carried.isEmpty, "something ended up on the cursor: ${menu.carried}")
            val gained = (0 until player.inventory.containerSize)
                .map(player.inventory::getItem)
                .filter { it.`is`(Items.STAINED_GLASS_PANE.black()) }
            helper.assertTrue(gained.isEmpty(), "the menu handed the player $gained")
            helper.assertTrue(
                menu.quickMoveStack(player, 0).isEmpty,
                "quickMoveStack moved something",
            )
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun theSlotAfterTheActionsDoesNothing(helper: GameTestHelper) {
        // Slot 17 is outside the action window and remains a blue pane.
        val player = MessageCapturingPlayer.join(helper, "TCSlot16")
        try {
            player.usesCrystal(tier = 3)
            player.messages.clear()
            CrystalMenu.openMenuOf(player)!!.clicked(17, 0, ContainerInput.PICKUP, player)

            helper.assertTrue(CrystalMenu.openMenuOf(player) != null, "slot 17 closed the menu")
            helper.assertTrue(player.messages.isEmpty(), "slot 17 said ${player.messages.map { it.string }}")
            helper.assertValueEqual(CrystalEnergy.energyOf(player), 5, "energy after clicking slot 17")
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    // ---- the destinations ----

    @GameTest
    fun adoubleClickSpendsOneEnergyAndTeleportsOnce(helper: GameTestHelper) {
        // The destination is queued rather than run inside the click, so both
        // clicks of a double-click can reach the queue before either has closed
        // the menu. Nucleus's synchronous close made this impossible; here the
        // menu identity is what makes the second click stale.
        val player = MessageCapturingPlayer.join(helper, "TCDouble")
        CrystalEnergy.setEnergy(player, 5)
        player.usesCrystal(tier = 3)
        val menu = CrystalMenu.openMenuOf(player)!!
        player.messages.clear()

        menu.clicked(EMBASSY_SLOT, 0, ContainerInput.PICKUP, player)
        menu.clicked(EMBASSY_SLOT, 0, ContainerInput.PICKUP, player)
        helper.runAfterDelay(1) {
            try {
                helper.assertValueEqual(
                    CrystalEnergy.energyOf(player),
                    4,
                    "energy after double-clicking one destination",
                )
                helper.assertOnlyMessage(
                    player,
                    Paint.info("You used one energy going to ", Paint.aqua("embassy")),
                    "a double-click should report one journey",
                )
                helper.succeed()
            } finally {
                player.leave()
            }
        }
    }

    @GameTest
    fun spawn1SendsThePlayerToSpawnTownForFree(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCSpawn")
        CrystalEnergy.setEnergy(player, 5)
        player.usesCrystal(tier = 3)
        player.messages.clear()

        helper.afterClick(player, SPAWN_SLOT, player) {
            // Story 30 names Primary's overworld, not just the coordinates.
            helper.assertValueEqual(
                player.level().dimension(),
                WorldsFeature.worlds!!.byId("primary")!!.dimension(DimensionRole.OVERWORLD),
                "the dimension spawn town is in",
            )
            helper.assertValueEqual(player.x, 16.5, "spawn x")
            helper.assertValueEqual(player.y, 71.0, "spawn y")
            helper.assertValueEqual(player.z, -15.5, "spawn z")
            helper.assertValueEqual(player.yRot, 180.0f, "spawn yaw")
            helper.assertValueEqual(player.xRot, 0.0f, "spawn pitch")
            helper.assertValueEqual(CrystalEnergy.energyOf(player), 5, "energy after a free destination")
            helper.assertOnlyMessage(
                player,
                Paint.info("You arrived at ", Paint.aqua("spawn 1")),
                "the free-arrival message",
            )
            helper.assertTrue(CrystalMenu.openMenuOf(player) == null, "the menu stayed open")
        }
    }

    @GameTest
    fun spawn2SendsThePlayerToTheRemoteSpawnForFree(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCSpawn2")
        CrystalEnergy.setEnergy(player, 5)
        player.usesCrystal(tier = 3)
        player.messages.clear()

        helper.afterClick(player, SPAWN2_SLOT, player) {
            helper.assertValueEqual(
                player.level().dimension(),
                WorldsFeature.worlds!!.byId("primary")!!.dimension(DimensionRole.OVERWORLD),
                "the dimension spawn 2 is in",
            )
            helper.assertValueEqual(player.x, 0.5, "spawn 2 x")
            helper.assertValueEqual(player.y, 67.5, "spawn 2 y")
            helper.assertValueEqual(player.z, 802816.5, "spawn 2 z")
            helper.assertValueEqual(player.yRot, 0.0f, "spawn 2 yaw")
            helper.assertValueEqual(player.xRot, 0.0f, "spawn 2 pitch")
            helper.assertValueEqual(CrystalEnergy.energyOf(player), 5, "energy after a free destination")
            helper.assertOnlyMessage(
                player,
                Paint.info("You arrived at ", Paint.aqua("spawn 2")),
                "the free-arrival message",
            )
        }
    }

    @GameTest
    fun bedRefusesAPlayerWithNowhereToWakeUpAndCostsNothing(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCNoBed")
        CrystalEnergy.setEnergy(player, 5)
        player.usesCrystal(tier = 3)
        player.messages.clear()

        helper.afterClick(player, BED_SLOT, player) {
            helper.assertOnlyMessage(
                player,
                Paint.error("You have no bed to go to"),
                "the no-bed refusal",
            )
            helper.assertValueEqual(CrystalEnergy.energyOf(player), 5, "energy after a refused destination")
        }
    }

    @GameTest
    fun bedSendsThePlayerToTheirRespawnPoint(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCBed")
        val bed = helper.absolutePos(BlockPos(1, 1, 1))
        // Forced, so vanilla honours it without needing an intact bed block —
        // this test is about the destination, not about bed validation.
        player.setRespawnPosition(
            ServerPlayer.RespawnConfig(
                LevelData.RespawnData.of(helper.level.dimension(), bed, 0.0f, 0.0f),
                true,
            ),
            false,
        )
        CrystalEnergy.setEnergy(player, 5)
        player.usesCrystal(tier = 3)
        player.messages.clear()

        helper.afterClick(player, BED_SLOT, player) {
            helper.assertTrue(
                player.blockPosition().distSqr(bed) <= 4.0,
                "the bed destination landed at ${player.blockPosition()}, not near $bed",
            )
            helper.assertValueEqual(CrystalEnergy.energyOf(player), 4, "energy after the bed destination")
            helper.assertOnlyMessage(
                player,
                Paint.info("You used one energy going to ", Paint.aqua("bed")),
                "the energy-spent message",
            )
        }
    }

    @GameTest
    fun embassyLandsInTheEmbassiesDimensionAndRecordsAnOrigin(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCEmbassy")
        CrystalEnergy.setEnergy(player, 5)
        player.standAt(helper, 1.0, 1.0, 1.0)
        player.usesCrystal(tier = 3)
        player.messages.clear()

        helper.afterClick(player, EMBASSY_SLOT, player) {
            helper.assertValueEqual(
                player.level().dimension(),
                EmbassiesFeature.DIMENSION,
                "the dimension the Embassy destination lands in",
            )
            helper.assertValueEqual(player.x, 0.5, "embassy x")
            helper.assertValueEqual(player.y, 1.0, "embassy y")
            helper.assertValueEqual(player.z, 0.5, "embassy z")
            helper.assertValueEqual(CrystalEnergy.energyOf(player), 4, "energy after the embassy destination")
            helper.assertOnlyMessage(
                player,
                Paint.info("You used one energy going to ", Paint.aqua("embassy")),
                "the energy-spent message",
            )
            // Ticket 01's teleport mixin records this for us; story 5-7 depend on it.
            helper.assertTrue(
                EmbassyOrigins.originOf(player) != null,
                "arriving by crystal recorded no origin to go back to",
            )
        }
    }

    @GameTest
    fun wildernessRefusesAndCostsNothing(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCWild")
        CrystalEnergy.setEnergy(player, 5)
        player.usesCrystal(tier = 3)
        player.messages.clear()

        helper.afterClick(player, WILDERNESS_SLOT, player) {
            helper.assertOnlyMessage(
                player,
                Paint.error("Sorry, this feature is not available yet"),
                "the Wilderness refusal",
            )
            helper.assertValueEqual(CrystalEnergy.energyOf(player), 5, "energy after Wilderness")
            helper.assertTrue(CrystalMenu.openMenuOf(player) == null, "Wilderness left the menu open")
        }
    }

    // Its own environment, which is to say its own batch: "no-one else is
    // online" is a claim about the whole player list, and tests inside a batch
    // run side by side. The id is what makes the batch — see the note on
    // theServerStoppingClosesEveryOpenCrystalMenu below.
    @GameTest(environment = "mctraveler-test:own_batch_crystal_solo")
    fun playerRefusesWhenNobodyElseIsOnline(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCAlone")
        // The claim is about the whole player list, so make it true rather than
        // hope it is: an earlier batch that left someone logged in would
        // otherwise turn this into a silent pass of the wrong branch.
        for (other in helper.level.server.playerList.players.toList()) {
            if (other !== player) helper.level.server.playerList.remove(other)
        }
        CrystalEnergy.setEnergy(player, 5)
        player.usesCrystal(tier = 3)
        player.messages.clear()

        helper.afterClick(player, PLAYER_SLOT, player) {
            helper.assertOnlyMessage(
                player,
                Paint.error("No-one else is online"),
                "the alone refusal",
            )
            helper.assertValueEqual(CrystalEnergy.energyOf(player), 5, "energy after being alone")
        }
    }

    @GameTest
    fun playerOpensAHeadForEveryoneElseOnline(helper: GameTestHelper) {
        val chooser = MessageCapturingPlayer.join(helper, "TCChooser")
        val other = MessageCapturingPlayer.join(helper, "TCChosen")
        CrystalEnergy.setEnergy(chooser, 5)
        chooser.usesCrystal(tier = 3)
        PacketCapture.drain(chooser)

        helper.afterClick(chooser, PLAYER_SLOT, chooser, other) {
            helper.assertValueEqual(
                PacketCapture.drainOf<ClientboundOpenScreenPacket>(chooser).last().title.string,
                CrystalMenu.PLAYERS_TITLE,
                "the head menu's title",
            )
            val menu = CrystalMenu.openMenuOf(chooser)
            helper.assertTrue(
                menu != null,
                "the Player destination opened no menu, left ${chooser.containerMenu.javaClass.simpleName}",
            )
            helper.assertValueEqual(menu!!.kind, CrystalMenu.Kind.PLAYERS, "the second menu's kind")
            val heads = menu.contents().filter { !it.isEmpty }
            helper.assertTrue(
                heads.any { head ->
                    head.`is`(Items.PLAYER_HEAD) &&
                        head.get(DataComponents.CUSTOM_NAME)?.string == "TCChosen" &&
                        head.get(DataComponents.PROFILE)?.name()?.orElse(null) == "TCChosen" &&
                        head.get(DataComponents.LORE)?.lines()?.map { it.string } ==
                        listOf("Click to teleport to this player")
                },
                "no head for TCChosen, found ${heads.map { it.get(DataComponents.CUSTOM_NAME)?.string }}",
            )
            // Opening the head GUI is not itself a destination.
            helper.assertValueEqual(CrystalEnergy.energyOf(chooser), 5, "energy after opening the head menu")
        }
    }

    // ---- the request round trip ----

    @GameTest(maxTicks = 200)
    fun clickingAHeadSpendsEnergyAndInvitesTheTarget(helper: GameTestHelper) {
        val requester = MessageCapturingPlayer.join(helper, "TCAsker")
        val target = MessageCapturingPlayer.join(helper, "TCAsked")
        CrystalRequests.clear()
        CrystalEnergy.setEnergy(requester, 5)
        requester.usesCrystal(tier = 3)

        helper.afterClick(requester, PLAYER_SLOT) {
            val heads = CrystalMenu.openMenuOf(requester)!!
            val slot = heads.contents().indexOfFirst {
                it.get(DataComponents.CUSTOM_NAME)?.string == "TCAsked"
            }
            helper.assertTrue(slot >= 0, "no head for the target")
            requester.messages.clear()
            target.messages.clear()

            helper.afterClick(requester, slot, requester, target) {
                helper.assertOnlyMessage(
                    requester,
                    Paint.success("Request sent to ", Paint.green("TCAsked")),
                    "the requester's confirmation",
                )
                helper.assertValueEqual(CrystalEnergy.energyOf(requester), 5, "energy after asking")

                val invitation = target.spokenMessages().singleOrNull()
                helper.assertTrue(
                    invitation != null,
                    "the target was told ${target.spokenMessages().map { it.string }}",
                )
                helper.assertValueEqual(
                    invitation!!.string,
                    "INFO TCAsker wants to teleport to you - click here to accept",
                    "the invitation text",
                )
                // The INFO prefix is not clickable and never was — Nucleus
                // appended its message to the prefix component too, so the
                // click event sits on the body the player actually reads.
                val click = invitation.clickEvents().singleOrNull()
                helper.assertTrue(
                    click is ClickEvent.RunCommand &&
                        click.command().removePrefix("/") ==
                        "${CrystalRequests.ACCEPT_COMMAND} TCAsker",
                    "the invitation is not clickable to accept, found ${invitation.clickEvents()}",
                )
                helper.assertTrue(
                    invitation.runsOf("TCAsker").all { it.color == "aqua" },
                    "the requester's name should be aqua in ${invitation.textRuns()}",
                )
                helper.assertTrue(
                    invitation.runsOf("here").all { it.color == "aqua" },
                    "\"here\" should be aqua in ${invitation.textRuns()}",
                )
            }
        }
    }

    @GameTest(maxTicks = 200)
    fun acceptingBringsTheRequesterOver(helper: GameTestHelper) {
        val requester = MessageCapturingPlayer.join(helper, "TCComeA")
        val acceptor = MessageCapturingPlayer.join(helper, "TCComeB")
        CrystalRequests.clear()
        CrystalEnergy.setEnergy(requester, 5)
        acceptor.standAt(helper, 3.0, 1.0, 3.0)
        requester.standAt(helper, 0.0, 1.0, 0.0)
        requester.asksToTeleportTo(acceptor)
        requester.messages.clear()
        acceptor.messages.clear()

        acceptor.runsHiddenCommand("${CrystalRequests.ACCEPT_COMMAND} TCComeA")
        helper.runAfterDelay(1) {
            try {
                helper.assertTrue(
                    requester.position().distanceTo(acceptor.position()) < 1.0,
                    "the requester is at ${requester.position()}, not at ${acceptor.position()}",
                )
                helper.assertOnlyMessage(
                    requester,
                    Paint.info(Paint.aqua("TCComeB"), " has accepted your request; you used one energy"),
                    "what the requester was told",
                )
                helper.assertValueEqual(CrystalEnergy.energyOf(requester), 4, "energy after acceptance")
                helper.assertOnlyMessage(
                    acceptor,
                    Paint.success("Request accepted"),
                    "what the acceptor was told",
                )
                helper.succeed()
            } finally {
                requester.leave()
                acceptor.leave()
            }
        }
    }

    @GameTest(maxTicks = 200)
    fun acceptingWithNoRequesterEnergyRefusesAndConsumesTheRequest(helper: GameTestHelper) {
        val requester = MessageCapturingPlayer.join(helper, "TCNoEnergyA")
        val acceptor = MessageCapturingPlayer.join(helper, "TCNoEnergyB")
        CrystalRequests.clear()
        CrystalEnergy.setEnergy(requester, 5)
        requester.asksToTeleportTo(acceptor)
        CrystalEnergy.setEnergy(requester, 0)
        requester.messages.clear()
        acceptor.messages.clear()

        acceptor.runsHiddenCommand("${CrystalRequests.ACCEPT_COMMAND} TCNoEnergyA")
        helper.runAfterDelay(1) {
            try {
                helper.assertOnlyMessage(
                    acceptor,
                    Paint.error("The requester has no energy"),
                    "the acceptor's no-energy refusal",
                )
                helper.assertOnlyMessage(
                    requester,
                    Paint.error("You have no energy for this request"),
                    "the requester's no-energy refusal",
                )
                acceptor.messages.clear()
                acceptor.runsHiddenCommand("${CrystalRequests.ACCEPT_COMMAND} TCNoEnergyA")
                helper.runAfterDelay(1) {
                    try {
                        helper.assertOnlyMessage(
                            acceptor,
                            Paint.error("No request found"),
                            "the no-energy request should be consumed",
                        )
                        helper.succeed()
                    } finally {
                        requester.leave()
                        acceptor.leave()
                    }
                }
            } catch (failure: Throwable) {
                requester.leave()
                acceptor.leave()
                throw failure
            }
        }
    }

    @GameTest(environment = "mctraveler-test:own_batch_crystal_timeout", maxTicks = 200)
    fun activeTimeoutSweepNotifiesTheRequester(helper: GameTestHelper) {
        val requester = MessageCapturingPlayer.join(helper, "TCTimeoutA")
        val target = MessageCapturingPlayer.join(helper, "TCTimeoutB")
        CrystalRequests.clear()
        requester.asksToTeleportTo(target)
        requester.messages.clear()
        target.messages.clear()
        CrystalRequests.backdate(CrystalRequests.TIMEOUT_TICKS + 1)

        helper.runAfterDelay((CrystalFeature.REGEN_CHECK_INTERVAL_TICKS * 2 + 1).toLong()) {
            try {
                CrystalRequests.sweep(helper.level.server)
                helper.assertOnlyMessage(
                    requester,
                    Paint.info("Your teleport request to ", Paint.aqua("TCTimeoutB"), " timed out"),
                    "the requester's timeout notice",
                )
                helper.assertTrue(target.spokenMessages().isEmpty(), "the target received a timeout notice")
                helper.succeed()
            } finally {
                requester.leave()
                target.leave()
            }
        }
    }

    @GameTest(maxTicks = 200)
    fun aRequestOlderThanSixThousandTicksIsRefusedAndSpent(helper: GameTestHelper) {
        val requester = MessageCapturingPlayer.join(helper, "TCLateA")
        val acceptor = MessageCapturingPlayer.join(helper, "TCLateB")
        CrystalRequests.clear()
        CrystalEnergy.setEnergy(requester, 5)
        requester.asksToTeleportTo(acceptor)
        CrystalRequests.backdate(CrystalRequests.TIMEOUT_TICKS + 1)
        acceptor.messages.clear()

        acceptor.runsHiddenCommand("${CrystalRequests.ACCEPT_COMMAND} TCLateA")
        helper.runAfterDelay(1) {
            try {
                helper.assertOnlyMessage(
                    acceptor,
                    Paint.error("Request timed out"),
                    "the timeout refusal",
                )
                // Consumed by the attempt: a second try finds nothing at all.
                acceptor.messages.clear()
                acceptor.runsHiddenCommand("${CrystalRequests.ACCEPT_COMMAND} TCLateA")
                helper.runAfterDelay(1) {
                    try {
                        helper.assertOnlyMessage(
                            acceptor,
                            Paint.error("No request found"),
                            "a timed-out request should have been consumed",
                        )
                        helper.succeed()
                    } finally {
                        requester.leave()
                        acceptor.leave()
                    }
                }
            } catch (failure: Throwable) {
                requester.leave()
                acceptor.leave()
                throw failure
            }
        }
    }

    @GameTest(maxTicks = 200)
    fun acceptingSomeoneWhoIsNotOnlineSaysSo(helper: GameTestHelper) {
        val acceptor = MessageCapturingPlayer.join(helper, "TCGhostB")
        acceptor.messages.clear()

        acceptor.runsHiddenCommand("${CrystalRequests.ACCEPT_COMMAND} NotHere")
        helper.runAfterDelay(1) {
            try {
                helper.assertOnlyMessage(
                    acceptor,
                    Paint.error(Paint.red("NotHere"), " is not online"),
                    "the unknown-player refusal",
                )
                helper.succeed()
            } finally {
                acceptor.leave()
            }
        }
    }

    @GameTest(maxTicks = 200)
    fun acceptingWithNoRequestSaysSo(helper: GameTestHelper) {
        val stranger = MessageCapturingPlayer.join(helper, "TCNoReqA")
        val acceptor = MessageCapturingPlayer.join(helper, "TCNoReqB")
        CrystalRequests.clear()
        acceptor.messages.clear()

        acceptor.runsHiddenCommand("${CrystalRequests.ACCEPT_COMMAND} TCNoReqA")
        helper.runAfterDelay(1) {
            try {
                helper.assertOnlyMessage(
                    acceptor,
                    Paint.error("No request found"),
                    "the missing-request refusal",
                )
                helper.succeed()
            } finally {
                stranger.leave()
                acceptor.leave()
            }
        }
    }

    @GameTest(maxTicks = 200)
    fun theWrongArityIsAnsweredWithSilence(helper: GameTestHelper) {
        // Nucleus cancelled the event and returned; the command exists nowhere,
        // so vanilla has nothing to complain about either.
        val player = MessageCapturingPlayer.join(helper, "TCArity")
        player.messages.clear()

        player.runsHiddenCommand(CrystalRequests.ACCEPT_COMMAND)
        player.runsHiddenCommand("${CrystalRequests.ACCEPT_COMMAND} one two")
        helper.runAfterDelay(1) {
            try {
                helper.assertTrue(
                    player.spokenMessages().isEmpty(),
                    "a malformed accept said ${player.spokenMessages().map { it.string }}",
                )
                helper.succeed()
            } finally {
                player.leave()
            }
        }
    }

    @GameTest(maxTicks = 200)
    fun aHeadWhoseOwnerHasLeftCostsNothing(helper: GameTestHelper) {
        val requester = MessageCapturingPlayer.join(helper, "TCGoneA")
        val leaver = MessageCapturingPlayer.join(helper, "TCGoneB")
        CrystalRequests.clear()
        CrystalEnergy.setEnergy(requester, 5)
        requester.usesCrystal(tier = 3)

        helper.afterClick(requester, PLAYER_SLOT) {
            val slot = CrystalMenu.openMenuOf(requester)!!.contents().indexOfFirst {
                it.get(DataComponents.CUSTOM_NAME)?.string == "TCGoneB"
            }
            helper.assertTrue(slot >= 0, "no head for the player about to leave")
            leaver.leave()
            requester.messages.clear()

            helper.afterClick(requester, slot, requester) {
                helper.assertOnlyMessage(
                    requester,
                    Paint.error(Paint.red("TCGoneB"), " is not online"),
                    "the departed-target refusal",
                )
                helper.assertValueEqual(CrystalEnergy.energyOf(requester), 5, "energy after a vanished target")
            }
        }
    }

    // ---- deviation 8, and story 36 ----

    @GameTest
    fun theAcceptCommandIsInNoCommandTree(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCHidden")
        try {
            val dispatcher = helper.level.server.commands.dispatcher
            helper.assertTrue(
                dispatcher.root.getChild(CrystalRequests.ACCEPT_COMMAND) == null,
                "${CrystalRequests.ACCEPT_COMMAND} is registered, so it would tab-complete (deviation 8)",
            )
            helper.assertTrue(
                player.suggestionsFor(CrystalRequests.ACCEPT_COMMAND.dropLast(6)).none {
                    it.contains(CrystalRequests.ACCEPT_COMMAND)
                },
                "the accept command was offered as a completion",
            )
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun closingTheMenuEndsTheSession(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "TCClose")
        try {
            player.usesCrystal(tier = 3)
            player.closeContainer()
            helper.assertTrue(CrystalMenu.openMenuOf(player) == null, "closing left the session open")
            // ... and the refusal is gone with it.
            player.messages.clear()
            player.usesCrystal(tier = 3)
            helper.assertTrue(
                CrystalMenu.openMenuOf(player) != null,
                "reopening after a close was refused: ${player.messages.map { it.string }}",
            )
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    // Its own environment, and so its own batch: closeAll reaches for every
    // player on the server, and tests inside a batch run side by side — in a
    // shared batch this test closes its neighbours' menus out from under them.
    //
    // The environment *id* is what buys that, not the file's contents: batches
    // are `groupingBy` the environment a test names, so every own-batch test
    // needs an id of its own. The `own_batch_*` files are deliberately identical
    // and deliberately not shared — merging them would put these tests back in
    // one batch, which is the very thing each of them cannot survive.
    @GameTest(environment = "mctraveler-test:own_batch_crystal_sweep")
    fun theServerStoppingClosesEveryOpenCrystalMenu(helper: GameTestHelper) {
        // SERVER_STOPPING itself cannot be reached from a gametest, so what it
        // calls is called directly (as EmbassiesGameTest does for its own stop hook).
        val player = MessageCapturingPlayer.join(helper, "TCStop")
        try {
            player.usesCrystal(tier = 3)
            helper.assertTrue(CrystalMenu.openMenuOf(player) != null, "the menu never opened")
            CrystalMenu.closeAll(helper.level.server)
            helper.assertTrue(CrystalMenu.openMenuOf(player) == null, "a crystal menu survived the server stopping")
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    private companion object {
        const val BED_SLOT = 11
        const val SPAWN_SLOT = 12
        const val SPAWN2_SLOT = 13
        const val PLAYER_SLOT = 14
        const val EMBASSY_SLOT = 15
        const val WILDERNESS_SLOT = 16

        /** tier, energy, whether the menu opens (story 27). */
        val TIER_ENERGY_CASES = listOf(
            Triple(1, 5, true),
            Triple(1, 4, false),
            Triple(2, 3, true),
            Triple(2, 2, false),
            Triple(3, 1, true),
            Triple(3, 0, false),
        )
    }
}

/**
 * The prefixes every message in the mod's message language carries
 * ([eu.mctraveler.text.Paint]).
 */
private val PAINT_PREFIXES = listOf("ERROR ", "SUCCESS ", "INFO ", "WARNING ", "USAGE ")

/**
 * The messages [this] player was sent by the mod itself.
 *
 * The gametest server broadcasts its own running commentary — every test
 * result, and every player any test joins — to everyone on the server, and all
 * of it lands in the same capture. Filtering to the Paint prefixes leaves
 * exactly the lines a feature chose to send.
 */
private fun MessageCapturingPlayer.spokenMessages(): List<Component> =
    messages.filter { message -> PAINT_PREFIXES.any(message.string::startsWith) }

/**
 * Asserts the mod said exactly one thing to [player], and that it was
 * [expected] — so a refusal that also spent energy, or a destination that
 * also complained, fails here.
 */
private fun GameTestHelper.assertOnlyMessage(
    player: MessageCapturingPlayer,
    expected: Component,
    what: String,
) {
    assertTrue(
        player.spokenMessages().singleOrNull() == expected,
        "$what: expected \"${expected.string}\", got ${player.spokenMessages().map { it.string }}",
    )
}

/** Every slot of the menu's own container, in order (the player's inventory excluded). */
private fun CrystalMenu.CrystalChestMenu.contents(): List<ItemStack> =
    (0 until container.containerSize).map(container::getItem)

/** Right-clicks a crystal of [tier] the way a client does: hold it, use it. */
private fun MessageCapturingPlayer.usesCrystal(tier: Int) {
    setItemInHand(InteractionHand.MAIN_HAND, CrystalItem.of(tier))
    gameMode.useItem(this, level(), mainHandItem, InteractionHand.MAIN_HAND)
}

/** Right-clicks a crystal at the top face of the block at [target]. */
private fun MessageCapturingPlayer.usesCrystalOn(
    helper: GameTestHelper,
    target: BlockPos,
    tier: Int = 3,
): InteractionResult {
    setItemInHand(InteractionHand.MAIN_HAND, CrystalItem.of(tier))
    val absolute = helper.absolutePos(target)
    val hit = BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false)
    return gameMode.useItemOn(this, level(), mainHandItem, InteractionHand.MAIN_HAND, hit)
}

/**
 * Sends the accept command as the invitation's click event does: a raw command
 * packet, since no dispatcher knows this command (deviation 8).
 */
private fun MessageCapturingPlayer.runsHiddenCommand(command: String) {
    connection.handleChatCommand(ServerboundChatCommandPacket(command))
}

/** Drives the whole ask, without going through the menu. */
private fun MessageCapturingPlayer.asksToTeleportTo(target: MessageCapturingPlayer) {
    CrystalRequests.send(this, CrystalMenu.Head(target.uuid, target.gameProfile.name))
}

/**
 * Clicks [slot] and runs [assertions] once the queued destination has had its
 * tick, succeeding the test and taking [cleanup]'s players off the server
 * whichever way it goes.
 */
private fun GameTestHelper.afterClick(
    player: MessageCapturingPlayer,
    slot: Int,
    vararg cleanup: MessageCapturingPlayer,
    assertions: () -> Unit,
) {
    player.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, player)
    runAfterDelay(1) {
        try {
            assertions()
            if (cleanup.isNotEmpty()) succeed()
        } finally {
            cleanup.forEach { it.leave() }
        }
    }
}

/** The runs of this message whose text is exactly [text]. */
private fun Component.runsOf(text: String): List<TextRun> = textRuns().filter { it.text == text }

/**
 * Every click event anywhere in this message, root and siblings alike —
 * "what happens if the player clicks this line", wherever the event is hung.
 */
private fun Component.clickEvents(): List<ClickEvent> =
    listOfNotNull(style.clickEvent) + siblings.flatMap { it.clickEvents() }
