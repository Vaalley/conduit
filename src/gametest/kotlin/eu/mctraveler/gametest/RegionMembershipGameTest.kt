package eu.mctraveler.gametest

import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/**
 * Region membership at the running-server seam (spec story 31): `/rg add` and
 * `/rg remove` with the Portal's caps, permission rules, and exact messages
 * (RegionFeature.ts; inventory §2.8).
 */
class RegionMembershipGameTest {

    // ---- /rg add ----

    @GameTest
    fun bareAddShowsUsage(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T13AddUse")
        player.runCommand("rg add")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.usage("/rg add <player>"),
            "the bare /rg add reply",
        )
        player.runCommand("region add")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.usage("/region add <player>"),
            "the bare /region add reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun addingSomeoneOfflineReportsThemNotFoundBeforeAnyRegionCheck(helper: GameTestHelper) {
        // Standing nowhere near a region: the Portal resolved the target
        // argument before the command body ran, so this error wins.
        val player = MessageCapturingPlayer.join(helper, "T13AddGone")
        player.standAt(helper, 0.0, 1.0, 0.0)
        player.runCommand("rg add Nobody")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.gray("Player ", Paint.red("Nobody"), " not found or is offline"),
            "the offline-target /rg add reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun addingOutsideAnyRegionErrors(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13AddOutA")
        val bob = MessageCapturingPlayer.join(helper, "T13AddOutB")
        alice.standAt(helper, 0.0, 1.0, 0.0)
        alice.runCommand("rg add T13AddOutB")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.error("You must stand in the region you want to add a resident to"),
            "the outside-region /rg add reply",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentAddsAnotherPlayer(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13AddA")
        val bob = MessageCapturingPlayer.join(helper, "T13AddB")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        alice.runCommand("rg add T13AddB")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.success(Paint.green("T13AddB"), " has been added to ", Paint.green("T13AddA's Place")),
            "the /rg add reply",
        )
        helper.assertTrue(region.isResident(bob.uuid), "the added player is not a member")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun addingAnExistingMemberErrors(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13DupA")
        val bob = MessageCapturingPlayer.join(helper, "T13DupB")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        alice.runCommand("rg add T13DupB")
        alice.runCommand("rg add T13DupB")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.error(Paint.red("T13DupB"), " is already a member of ", Paint.red("T13DupA's Place")),
            "the duplicate /rg add reply",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberCannotAdd(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13PermA")
        val bob = MessageCapturingPlayer.join(helper, "T13PermB")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        bob.standAt(helper, 3.0, 1.0, 0.0)
        bob.runCommand("rg add T13PermA")
        helper.assertValueEqual(
            bob.messages.last(),
            Paint.error("You are not a member of this region"),
            "the non-member /rg add reply",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun anAdminMayAddToAnotherPlayersRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13OpA")
        val admin = MessageCapturingPlayer.join(helper, "T13OpAdmin")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        admin.makeAdmin()
        admin.standAt(helper, 3.0, 1.0, 0.0)
        admin.runCommand("rg add T13OpAdmin")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.success(Paint.green("T13OpAdmin"), " has been added to ", Paint.green("T13OpA's Place")),
            "the admin /rg add reply",
        )
        helper.assertTrue(region.isResident(admin.uuid), "the admin did not get added")
        alice.leave()
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun aParentResidentMayAddToASubRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SubA")
        val bob = MessageCapturingPlayer.join(helper, "T13SubB")
        val carol = MessageCapturingPlayer.join(helper, "T13SubC")
        val parent = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 7.0)
        val sub = createRegion(helper, alice, 0.0 to 0.0, 4.0 to 1.0)
        helper.assertTrue(sub.parent === parent, "the sub-region is not nested in the parent")

        // Alice keeps the parent; Bob alone lives in the sub-region.
        sub.members.remove(alice.uuid)
        sub.members.add(bob.uuid)
        RegionsFeature.requireService().save()

        alice.runCommand("rg add T13SubC")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.success(Paint.green("T13SubC"), " has been added to ", Paint.green(sub.title)),
            "the parent-resident /rg add reply",
        )
        helper.assertTrue(sub.isResident(carol.uuid), "the parent resident's add did not take")
        alice.leave()
        bob.leave()
        carol.leave()
        helper.succeed()
    }

    @GameTest
    fun theNinetyNineMemberCapIsEnforced(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13CapA")
        val bob = MessageCapturingPlayer.join(helper, "T13CapB")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)
        repeat(98) { region.members.add(UUID.randomUUID()) } // Alice makes 99
        RegionsFeature.requireService().save()

        alice.runCommand("rg add T13CapB")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.error("Regions may only have 99 members"),
            "the full-region /rg add reply",
        )
        helper.assertFalse(region.isResident(bob.uuid), "the 100th member was added anyway")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    // ---- /rg remove ----

    @GameTest
    fun bareRemoveShowsUsage(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T13RemUse")
        player.runCommand("rg remove")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.usage("/rg remove <player>"),
            "the bare /rg remove reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun removingOutsideAnyRegionErrors(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T13RemOut")
        player.standAt(helper, 0.0, 1.0, 0.0)
        player.runCommand("rg remove T13RemOut")
        helper.assertValueEqual(
            player.messages.last(),
            Paint.error("You must stand in the region you want to remove a resident from"),
            "the outside-region /rg remove reply",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun aNonMemberCannotRemove(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13RemPrmA")
        val bob = MessageCapturingPlayer.join(helper, "T13RemPrmB")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        bob.standAt(helper, 3.0, 1.0, 0.0)
        bob.runCommand("rg remove T13RemPrmA")
        helper.assertValueEqual(
            bob.messages.last(),
            Paint.error("You are not a member of this region"),
            "the non-member /rg remove reply",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun removingSomeoneWhoIsNotAMemberErrors(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13NotMemA")
        val bob = MessageCapturingPlayer.join(helper, "T13NotMemB")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        alice.runCommand("rg remove T13NotMemB")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.error(Paint.red("T13NotMemB"), " is not a member of ", Paint.red("T13NotMemA's Place")),
            "the not-a-member /rg remove reply",
        )
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun aRegionCannotBeEmptied(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13OnlyA")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)

        alice.runCommand("rg remove T13OnlyA")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.error(Paint.red("T13OnlyA"), " is the only member of ", Paint.red("T13OnlyA's Place")),
            "the only-member /rg remove reply",
        )
        helper.assertTrue(region.isResident(alice.uuid), "the last member was removed anyway")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun aResidentRemovesAMember(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13RemA")
        val bob = MessageCapturingPlayer.join(helper, "T13RemB")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)
        alice.runCommand("rg add T13RemB")

        alice.runCommand("rg remove T13RemB")
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.success(Paint.green("T13RemB"), " has been removed from ", Paint.green("T13RemA's Place")),
            "the /rg remove reply",
        )
        helper.assertFalse(region.isResident(bob.uuid), "the removed player is still a member")
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest
    fun anAdminMayRemoveFromAnotherPlayersRegion(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13RemOpA")
        val bob = MessageCapturingPlayer.join(helper, "T13RemOpB")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)
        alice.runCommand("rg add T13RemOpB")

        val admin = MessageCapturingPlayer.join(helper, "T13RemOp")
        admin.makeAdmin()
        admin.standAt(helper, 3.0, 1.0, 0.0)
        admin.runCommand("rg remove T13RemOpB")
        helper.assertValueEqual(
            admin.messages.last(),
            Paint.success(Paint.green("T13RemOpB"), " has been removed from ", Paint.green("T13RemOpA's Place")),
            "the admin /rg remove reply",
        )
        helper.assertFalse(region.isResident(bob.uuid), "the admin's removal did not take")
        alice.leave()
        bob.leave()
        admin.leave()
        helper.succeed()
    }

    @GameTest
    fun anOfflineMemberCanBeRemovedByName(helper: GameTestHelper) {
        // Names resolve from the real name cache, not just online players
        // (deviation 10) — so a member who has logged off is still removable.
        val alice = MessageCapturingPlayer.join(helper, "T13OffA")
        val bob = MessageCapturingPlayer.join(helper, "T13OffB")
        val region = createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)
        alice.runCommand("rg add T13OffB")
        bob.leave()

        alice.runCommand("rg remove t13offb") // and by any casing
        helper.assertValueEqual(
            alice.messages.last(),
            Paint.success(Paint.green("t13offb"), " has been removed from ", Paint.green("T13OffA's Place")),
            "the offline-member /rg remove reply",
        )
        helper.assertFalse(region.isResident(bob.uuid), "the offline member is still a member")
        alice.leave()
        helper.succeed()
    }

    @GameTest
    fun removeSuggestsTheCurrentRegionsMembersByPrefix(helper: GameTestHelper) {
        val alice = MessageCapturingPlayer.join(helper, "T13SugAlice")
        val bob = MessageCapturingPlayer.join(helper, "T13SugBob")
        val carol = MessageCapturingPlayer.join(helper, "T13SugCarl")
        createRegion(helper, alice, 0.0 to 0.0, 7.0 to 1.0)
        alice.runCommand("rg add T13SugBob")
        alice.runCommand("rg add T13SugCarl")

        helper.assertValueEqual(
            alice.suggestionsFor("rg remove T13Sug"),
            listOf("T13SugAlice", "T13SugBob", "T13SugCarl"),
            "the /rg remove suggestions inside a region",
        )
        helper.assertValueEqual(
            alice.suggestionsFor("rg remove t13sugb"),
            listOf("T13SugBob"),
            "the prefix-filtered /rg remove suggestions",
        )
        // Outside every region there is nobody to suggest.
        carol.standAt(helper, 0.0, 1.0, 6.0)
        helper.assertValueEqual(
            carol.suggestionsFor("rg remove T13Sug"),
            emptyList<String>(),
            "the /rg remove suggestions outside a region",
        )
        alice.leave()
        bob.leave()
        carol.leave()
        helper.succeed()
    }
}
