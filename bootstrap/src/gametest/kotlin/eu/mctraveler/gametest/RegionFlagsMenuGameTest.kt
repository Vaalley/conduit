package eu.mctraveler.gametest

import eu.mctraveler.region.RegionFlagsMenu
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.region.RegionTracker
import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The region-flags GUI (`/rg flags`): opening, item content, toggling, the
 * admin sub-page round-trip, `EMBASSY`'s read-only refusal, and the three
 * security cases the plan calls out explicitly — a chest GUI is a known
 * exploit surface, and every one of these is a deliberate, targeted defense
 * rather than an incidental side effect of [RegionFlagsMenu]'s shape.
 *
 * Modeled on [CrystalMenuGameTest]'s `afterClick` pattern: every click is
 * swallowed and its mutation deferred one server tick
 * ([RegionFlagsMenu.RegionFlagsChestMenu.clicked]), so assertions run after
 * [GameTestHelper.runAfterDelay].
 */
class RegionFlagsMenuGameTest {

    // ---- opening ----

    @GameTest
    fun rgFlagsOpensTheMainPage(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFOpen")
        try {
            createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
            PacketCapture.drain(alice)

            RegionFlagsMenu.openMain(alice, RegionTracker.regionOf(alice)!!)

            val menu = RegionFlagsMenu.openMenuOf(alice)
            helper.assertTrue(menu != null, "opening the main page opened no menu")
            helper.assertValueEqual(menu!!.page, RegionFlagsMenu.Page.MAIN, "the page opened")
            val opened = PacketCapture.drainOf<ClientboundOpenScreenPacket>(alice).single()
            helper.assertValueEqual(opened.title.string, "Region flag settings", "the menu title")
            helper.succeed()
        } finally {
            alice.leave()
        }
    }

    @GameTest
    fun aNonResidentNonAdminCannotOpenTheMainPage(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFDenyA")
        val bob = MessageCapturingPlayer.join(helper, "TFDenyB")
        try {
            val region = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)

            RegionFlagsMenu.openMain(bob, region)

            helper.assertTrue(RegionFlagsMenu.openMenuOf(bob) == null, "a stranger opened the main page")
            helper.assertValueEqual(
                bob.messages.last(),
                Paint.error("You are not a member of this region"),
                "the non-member refusal",
            )
            helper.succeed()
        } finally {
            alice.leave()
            bob.leave()
        }
    }

    // ---- item content ----

    @GameTest
    fun anAllowedDisallowedFlagShowsItsStatusAndLore(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFItemA")
        try {
            val region = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
            RegionFlagsMenu.openMain(alice, region)
            val contents = RegionFlagsMenu.openMenuOf(alice)!!.contents()

            // EXPLOSIONS: first item, main page slot 0, defaultAllowed = false.
            val stack = contents[0]
            helper.assertTrue(stack.`is`(Items.TNT), "slot 0 should be TNT")
            helper.assertValueEqual(
                stack.get(DataComponents.CUSTOM_NAME)!!.string,
                "Explosions",
                "the EXPLOSIONS item name",
            )
            val lore = stack.get(DataComponents.LORE)?.lines().orEmpty().map { it.string }
            helper.assertTrue(lore.isNotEmpty(), "no lore on the EXPLOSIONS item")
            helper.assertValueEqual(lore.first(), "Disallowed", "the default EXPLOSIONS status")
            helper.succeed()
        } finally {
            alice.leave()
        }
    }

    @GameTest
    fun aTrueFalseFlagShowsTrueOrFalseRatherThanAllowedDisallowed(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFItemB")
        try {
            val region = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
            RegionFlagsMenu.openMain(alice, region)
            val contents = RegionFlagsMenu.openMenuOf(alice)!!.contents()

            // ANIMAL_PROTECTION: third item of row 2, main page slot 19, seeded true.
            val stack = contents[19]
            helper.assertValueEqual(
                stack.get(DataComponents.CUSTOM_NAME)!!.string,
                "Animal protection",
                "the ANIMAL_PROTECTION item name",
            )
            val lore = stack.get(DataComponents.LORE)?.lines().orEmpty().map { it.string }
            helper.assertValueEqual(lore.first(), "True", "the default ANIMAL_PROTECTION status")
            helper.succeed()
        } finally {
            alice.leave()
        }
    }

    @GameTest
    fun theAdminButtonIsPresentForAnAdminAndAFillerOtherwise(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFBtnA")
        val bob = MessageCapturingPlayer.join(helper, "TFBtnB")
        try {
            val region = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
            bob.makeAdmin()

            RegionFlagsMenu.openMain(alice, region)
            val aliceButton = RegionFlagsMenu.openMenuOf(alice)!!.contents()[26]
            helper.assertFalse(
                aliceButton.`is`(Items.REPEATING_COMMAND_BLOCK),
                "a non-admin saw the admin button",
            )
            alice.leave()

            region.members.add(bob.uuid)
            RegionFlagsMenu.openMain(bob, region)
            val bobButton = RegionFlagsMenu.openMenuOf(bob)!!.contents()[26]
            helper.assertTrue(
                bobButton.`is`(Items.REPEATING_COMMAND_BLOCK),
                "an admin saw no admin button",
            )
            helper.succeed()
        } finally {
            bob.leave()
        }
    }

    // ---- toggling ----

    @GameTest
    fun clickingAFlagTogglesItAndRedrawsTheItemInPlace(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFToggleA")
        try {
            val region = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
            RegionFlagsMenu.openMain(alice, region)
            helper.assertFalse("EXPLOSIONS" in region.flags, "precondition: EXPLOSIONS starts off")

            RegionFlagsMenu.openMenuOf(alice)!!.clicked(0, 0, ContainerInput.PICKUP, alice)

            helper.runAfterDelay(1) {
                helper.assertTrue("EXPLOSIONS" in region.flags, "the click did not add EXPLOSIONS")
                val stack = RegionFlagsMenu.openMenuOf(alice)!!.contents()[0]
                val lore = stack.get(DataComponents.LORE)?.lines().orEmpty().map { it.string }
                helper.assertValueEqual(lore.first(), "Allowed", "the redrawn EXPLOSIONS status")
                alice.leave()
                helper.succeed()
            }
        } catch (t: Throwable) {
            alice.leave()
            throw t
        }
    }

    // ---- admin sub-page round trip ----

    @GameTest
    fun theAdminButtonOpensTheAdminPageAndBackReturnsToMain(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFAdminA")
        try {
            val region = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
            alice.makeAdmin()
            RegionFlagsMenu.openMain(alice, region)

            RegionFlagsMenu.openMenuOf(alice)!!.clicked(26, 0, ContainerInput.PICKUP, alice)

            helper.runAfterDelay(1) {
                val adminMenu = RegionFlagsMenu.openMenuOf(alice)
                helper.assertTrue(adminMenu != null, "the admin button opened no menu")
                helper.assertValueEqual(adminMenu!!.page, RegionFlagsMenu.Page.ADMIN, "the page after the admin button")
                val embassyItem = adminMenu.contents()[4]
                helper.assertTrue(
                    embassyItem.`is`(Items.SPYGLASS),
                    "slot 4 should be the read-only EMBASSY item",
                )

                adminMenu.clicked(26, 0, ContainerInput.PICKUP, alice)
                helper.runAfterDelay(1) {
                    val backToMain = RegionFlagsMenu.openMenuOf(alice)
                    helper.assertTrue(backToMain != null, "back opened no menu")
                    helper.assertValueEqual(backToMain!!.page, RegionFlagsMenu.Page.MAIN, "the page after back")
                    alice.leave()
                    helper.succeed()
                }
            }
        } catch (t: Throwable) {
            alice.leave()
            throw t
        }
    }

    @GameTest
    fun aNonAdminCannotOpenTheAdminPage(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFAdminDenyA")
        try {
            val region = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)

            RegionFlagsMenu.openAdmin(alice, region)

            helper.assertTrue(RegionFlagsMenu.openMenuOf(alice) == null, "a non-admin opened the admin page")
            helper.assertValueEqual(
                alice.messages.last(),
                Paint.error("You must be an admin to use this command"),
                "the non-admin admin-page refusal",
            )
            helper.succeed()
        } finally {
            alice.leave()
        }
    }

    @GameTest
    fun clickingTheReadOnlyEmbassyItemRefusesWithTheExistingMessage(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFEmbassyA")
        try {
            val region = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
            alice.makeAdmin()
            RegionFlagsMenu.openAdmin(alice, region)

            RegionFlagsMenu.openMenuOf(alice)!!.clicked(4, 0, ContainerInput.PICKUP, alice)

            helper.runAfterDelay(1) {
                helper.assertFalse("EMBASSY" in region.flags, "clicking EMBASSY toggled it")
                helper.assertValueEqual(
                    alice.messages.last(),
                    Paint.error("You cannot toggle the embassy flag"),
                    "the embassy-toggle refusal",
                )
                alice.leave()
                helper.succeed()
            }
        } catch (t: Throwable) {
            alice.leave()
            throw t
        }
    }

    // ---- security safeguards ----

    @GameTest
    fun reopeningWhileAlreadyOpenIsRefused(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFReopenA")
        try {
            val region = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
            RegionFlagsMenu.openMain(alice, region)
            val firstMenu = RegionFlagsMenu.openMenuOf(alice)
            helper.assertTrue(firstMenu != null, "the first open failed outright")

            RegionFlagsMenu.openMain(alice, region)

            helper.assertTrue(
                RegionFlagsMenu.openMenuOf(alice) === firstMenu,
                "a second menu instance replaced the first",
            )
            helper.assertValueEqual(
                alice.messages.last(),
                Paint.error("You already have region flags open"),
                "the already-open refusal",
            )
            helper.succeed()
        } finally {
            alice.leave()
        }
    }

    @GameTest
    fun aToggleAfterLosingResidencyMidSessionIsRefused(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFLoseResA")
        try {
            val region = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
            RegionFlagsMenu.openMain(alice, region)
            helper.assertFalse("EXPLOSIONS" in region.flags, "precondition: EXPLOSIONS starts off")

            // Removed mid-session — the menu they still have open must not
            // let them keep acting with standing they no longer have.
            region.members.remove(alice.uuid)

            RegionFlagsMenu.openMenuOf(alice)!!.clicked(0, 0, ContainerInput.PICKUP, alice)

            helper.runAfterDelay(1) {
                helper.assertFalse(
                    "EXPLOSIONS" in region.flags,
                    "a toggle succeeded after the clicker stopped being a resident",
                )
                alice.leave()
                helper.succeed()
            }
        } catch (t: Throwable) {
            alice.leave()
            throw t
        }
    }

    @GameTest
    fun aToggleAfterTheRegionIsDeletedMidSessionIsRefusedWithoutThrowing(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "TFDeleteA")
        try {
            val region = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 4.0)
            RegionFlagsMenu.openMain(alice, region)

            RegionsFeature.requireService().remove(region)

            RegionFlagsMenu.openMenuOf(alice)!!.clicked(0, 0, ContainerInput.PICKUP, alice)

            helper.runAfterDelay(1) {
                // No throw reaching here is most of the assertion; the flag
                // set on the now-detached Region must also be untouched.
                helper.assertFalse(
                    "EXPLOSIONS" in region.flags,
                    "a toggle mutated a region already removed from the tree",
                )
                alice.leave()
                helper.succeed()
            }
        } catch (t: Throwable) {
            alice.leave()
            throw t
        }
    }
}

/** Every slot of the menu's own container, in order (the player's inventory excluded). */
private fun RegionFlagsMenu.RegionFlagsChestMenu.contents(): List<ItemStack> =
    (0 until container.containerSize).map(container::getItem)
