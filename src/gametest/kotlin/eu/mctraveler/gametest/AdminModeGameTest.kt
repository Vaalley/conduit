package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.admin.AdminFeature
import eu.mctraveler.admin.AdminInventory
import eu.mctraveler.admin.StaffAccess
import eu.mctraveler.rank.Rank
import eu.mctraveler.rank.RankFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks

class AdminModeGameTest {

    private fun usable(server: MinecraftServer, player: TestPlayer, root: String): Boolean {
        val node = server.commands.dispatcher.root.getChild(root) ?: return false
        return node.canUse(player.player.createCommandSourceStack())
    }

    private fun op(server: MinecraftServer, player: TestPlayer) {
        // A real permission level: the test server's default would give an operator none at all.
        server.playerList.op(
            player.player.nameAndId(),
            java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER),
            java.util.Optional.empty(),
        )
    }

    private fun moderator(player: TestPlayer) {
        checkNotNull(MCTraveler.persistence).players.setRank(player.player.uuid, Rank.MODERATOR.name)
    }

    private fun cleanup(server: MinecraftServer, vararg players: TestPlayer) {
        players.forEach {
            server.playerList.deop(it.player.nameAndId())
            it.disconnect()
        }
    }

    @GameTest(maxTicks = 100)
    fun operatorIsAnOrdinaryDonatorUntilAdminModeIsOn(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "AdmOffOp").also { op(server, it) }

        helper.runAfterDelay(2) {
            helper.assertFalse(StaffAccess.isActive(admin.player), "admin mode must start off")
            helper.assertFalse(usable(server, admin, "gamemode"), "/gamemode must be gone while admin mode is off")
            helper.assertFalse(usable(server, admin, "ban"), "/ban must be gone while admin mode is off")
            helper.assertFalse(usable(server, admin, "vanish"), "/vanish must be gone while admin mode is off")
            helper.assertTrue(usable(server, admin, "admin"), "/admin must be offered to an operator")
            helper.assertTrue(RankFeature.hasDonatorPerks(admin.player), "an operator keeps Donator perks")
            cleanup(server, admin)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 120)
    fun adminModeSwapsInventoryForCreativeAndBack(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "AdmSwapOp").also { op(server, it) }
        admin.player.inventory.setItem(0, ItemStack(Items.DIAMOND, 7))
        admin.player.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.IRON_HELMET))

        helper.runAfterDelay(2) {
            admin.runCommand("admin")
            helper.assertTrue(StaffAccess.isFullAdmin(admin.player), "admin mode should be on")
            helper.assertTrue(admin.player.gameMode.gameModeForPlayer == GameType.CREATIVE, "admin mode puts an operator in creative")
            helper.assertTrue(admin.player.inventory.getItem(0).isEmpty, "the inventory is cleared")
            helper.assertTrue(admin.player.getItemBySlot(EquipmentSlot.HEAD).isEmpty, "worn armour is stashed too")
            helper.assertTrue(AdminInventory.hasSaved(server, admin.player.uuid), "the real inventory is on disk while it is cleared")
            helper.assertTrue(
                admin.systemMessages().any { it.string.contains("Don't forget to turn it off") },
                "the reminder was ${admin.systemMessages()}",
            )
            helper.assertTrue(usable(server, admin, "gamemode"), "/gamemode returns in admin mode")
            helper.assertTrue(usable(server, admin, "vanish"), "/vanish returns in admin mode")

            admin.runCommand("admin")
            helper.assertFalse(StaffAccess.isActive(admin.player), "second /admin switches it off")
            helper.assertTrue(admin.player.gameMode.gameModeForPlayer == GameType.SURVIVAL, "back to survival")
            helper.assertValueEqual(admin.player.inventory.getItem(0).count, 7, "the stashed stack is back")
            helper.assertTrue(admin.player.getItemBySlot(EquipmentSlot.HEAD).`is`(Items.IRON_HELMET), "the helmet is back")
            helper.assertFalse(AdminInventory.hasSaved(server, admin.player.uuid), "the stash file is gone once restored")
            cleanup(server, admin)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 120)
    fun leavingInAdminModeGivesTheInventoryBack(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "AdmQuitOp").also { op(server, it) }
        admin.player.inventory.setItem(3, ItemStack(Items.EMERALD, 5))

        helper.runAfterDelay(2) {
            admin.runCommand("admin")
            val player = admin.player
            admin.disconnect()
            helper.assertFalse(StaffAccess.isOn(player.uuid), "admin mode does not survive a disconnect")
            helper.assertValueEqual(player.inventory.getItem(3).count, 5, "the inventory is handed back on disconnect")
            server.playerList.deop(player.nameAndId())
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 120)
    fun moderatorsGetOnlyTheirCommandsAndKeepSurvival(helper: GameTestHelper) {
        val server = helper.level.server
        val moderator = TestPlayer.join(server, "AdmModOne").also { moderator(it) }
        moderator.player.inventory.setItem(0, ItemStack(Items.APPLE, 3))
        val modeBefore = moderator.player.gameMode.gameModeForPlayer

        helper.runAfterDelay(2) {
            helper.assertFalse(usable(server, moderator, "ban"), "moderator commands are off until admin mode")
            helper.assertTrue(usable(server, moderator, "admin"), "/admin is offered to moderators")
            moderator.runCommand("admin")
            helper.assertTrue(StaffAccess.isActive(moderator.player), "moderator admin mode on")
            helper.assertTrue(moderator.player.gameMode.gameModeForPlayer == modeBefore, "moderators keep their game mode")
            helper.assertValueEqual(moderator.player.inventory.getItem(0).count, 3, "moderators keep their items")

            val allowed = listOf(
                "tp", "teleport", "kill", "weather", "time", "whitelist", "recipe",
                "ban", "unban", "pardon", "banlist", "kick", "warn", "warnings", "unwarn", "history", "note",
                "whois", "inspect", "lookup", "invsee", "vanish", "embassy", "set-teleportation-crystal-energy",
                "rank", "back", "region", "store",
            )
            allowed.forEach { name -> helper.assertTrue(usable(server, moderator, name), "/$name should be usable by a moderator") }
            val denied = listOf("gamemode", "give", "op", "deop", "stop", "execute", "setblock", "fill", "mute", "unmute", "economy", "trigger")
            denied.forEach { name -> helper.assertFalse(usable(server, moderator, name), "/$name must not be usable by a moderator") }
            cleanup(server, moderator)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 120)
    fun triggerIsForFullAdminsOnly(helper: GameTestHelper) {
        val server = helper.level.server
        val plain = TestPlayer.join(server, "AdmTrigPlain")
        val admin = TestPlayer.join(server, "AdmTrigOp").also { op(server, it) }

        helper.runAfterDelay(2) {
            helper.assertFalse(usable(server, plain, "trigger"), "/trigger hidden from ordinary players")
            helper.assertFalse(usable(server, admin, "trigger"), "/trigger hidden while admin mode is off")
            admin.runCommand("admin")
            helper.assertTrue(usable(server, admin, "trigger"), "/trigger available to a full admin")
            cleanup(server, plain, admin)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 120)
    fun moderatorsOnlyRankBelowThemselves(helper: GameTestHelper) {
        val server = helper.level.server
        val moderator = TestPlayer.join(server, "AdmRankMod").also { moderator(it) }
        val target = TestPlayer.join(server, "AdmRankTarget")
        val other = TestPlayer.join(server, "AdmRankOther").also { moderator(it) }

        helper.runAfterDelay(2) {
            moderator.runCommand("admin")
            moderator.runCommand("rank set AdmRankTarget donator")
            helper.assertTrue(RankFeature.rankOf(target.player) == Rank.DONATOR, "a moderator can grant Donator")
            moderator.runCommand("rank set AdmRankTarget moderator")
            helper.assertTrue(RankFeature.rankOf(target.player) == Rank.DONATOR, "a moderator cannot grant Moderator")
            moderator.runCommand("rank set AdmRankOther traveler")
            helper.assertTrue(RankFeature.rankOf(other.player) == Rank.MODERATOR, "a moderator cannot demote a moderator")
            cleanup(server, moderator, target, other)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 120)
    fun adminsCanMakeModerators(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "AdmMakeOp").also { op(server, it) }
        val target = TestPlayer.join(server, "AdmMakeTarget")

        helper.runAfterDelay(2) {
            admin.runCommand("admin")
            admin.runCommand("rank set AdmMakeTarget moderator")
            helper.assertTrue(RankFeature.rankOf(target.player) == Rank.MODERATOR, "an admin can grant Moderator")
            cleanup(server, admin, target)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 120)
    fun backReturnsFromATeleport(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "AdmBackOp").also { op(server, it) }
        val level = helper.level
        val start = helper.absolutePos(BlockPos(2, 2, 2))
        admin.moveTo(level, start.x + 0.5, start.y.toDouble(), start.z + 0.5)

        helper.runAfterDelay(2) {
            admin.runCommand("admin")
            helper.assertTrue(AdminFeature.backSpot(admin.player.uuid) == null, "nothing to go back to yet")
            admin.runCommand("tp ${start.x + 6} ${start.y} ${start.z + 6}")
            helper.assertTrue(AdminFeature.backSpot(admin.player.uuid) != null, "/tp records where the admin was")
            admin.runCommand("back")
            helper.assertTrue(
                admin.player.distanceToSqr(start.x + 0.5, start.y.toDouble(), start.z + 0.5) < 1.0,
                "/back returns to the start, got ${admin.player.position()}",
            )
            helper.assertTrue(AdminFeature.backSpot(admin.player.uuid) == null, "/back is used up")
            cleanup(server, admin)
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun landingFindsTheFirstSafeBlockBelow(helper: GameTestHelper) {
        helper.setBlock(BlockPos(1, 1, 1), Blocks.STONE)
        val above = helper.absolutePos(BlockPos(1, 8, 1))
        val floor = helper.absolutePos(BlockPos(1, 1, 1))
        val found = AdminFeature.firstSafeAtOrBelow(helper.level, above)
        helper.assertTrue(found == floor.above(), "expected ${floor.above()}, found $found")
        helper.succeed()
    }

    @GameTest(maxTicks = 100)
    fun landingRefusesMagmaAsAFloor(helper: GameTestHelper) {
        helper.setBlock(BlockPos(2, 1, 2), Blocks.MAGMA_BLOCK)
        val found = AdminFeature.firstSafeAtOrBelow(helper.level, helper.absolutePos(BlockPos(2, 3, 2)))
        helper.assertTrue(found == null, "magma is not a safe floor, found $found")
        helper.succeed()
    }

    @GameTest(maxTicks = 100)
    fun landingRefusesWater(helper: GameTestHelper) {
        helper.setBlock(BlockPos(3, 1, 3), Blocks.STONE)
        helper.setBlock(BlockPos(3, 2, 3), Blocks.WATER)
        val found = AdminFeature.firstSafeAtOrBelow(helper.level, helper.absolutePos(BlockPos(3, 3, 3)))
        helper.assertTrue(found == null, "water is not safe to land in, found $found")
        helper.succeed()
    }
}
