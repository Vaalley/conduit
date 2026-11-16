package eu.mctraveler.gametest

import eu.mctraveler.admin.StaffAccess
import eu.mctraveler.crystal.CrystalSpawns
import eu.mctraveler.rank.Rank
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level

class SetSpawnGameTest {

    private fun staff(server: MinecraftServer, name: String, moderator: Boolean): TestPlayer {
        val player = TestPlayer.join(server, name)
        if (moderator) {
            checkNotNull(eu.mctraveler.MCTraveler.persistence).players.setRank(player.player.uuid, Rank.MODERATOR.name)
        } else {
            server.playerList.op(player.player.nameAndId())
        }
        StaffAccess.setOnForTests(player.player.uuid, true)
        return player
    }

    @GameTest(maxTicks = 100)
    fun setspawnCentresTheSpotAndSnapsTheFacing(helper: GameTestHelper) {
        val server = helper.level.server
        val mod = staff(server, "SetSpawnMod", moderator = true)
        val overworld = server.getLevel(Level.OVERWORLD)!!
        val before = CrystalSpawns.definitions()[1]
        mod.player.teleportTo(overworld, 1234.8, 80.0, -567.2, emptySet(), 100f, 33f, true)

        helper.runAfterDelay(2) {
            try {
                mod.runCommand("setspawn spawn2")
                val after = CrystalSpawns.definitions()[1]
                helper.assertValueEqual(after.x, 1234.5, "x is the middle of the block")
                helper.assertValueEqual(after.z, -567.5, "z is the middle of the block")
                helper.assertValueEqual(after.y, 80.0, "y is where they stand")
                helper.assertValueEqual(after.yaw, 90f, "yaw snapped to the nearest compass direction")
                helper.assertValueEqual(after.name, before.name, "the spawn keeps its name")
            } finally {
                CrystalSpawns.define(1, before)
                mod.disconnect()
            }
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun spawnOneAlsoBecomesTheWorldSpawn(helper: GameTestHelper) {
        val server = helper.level.server
        val mod = staff(server, "SetSpawnWorld", moderator = true)
        val overworld = server.getLevel(Level.OVERWORLD)!!
        val before = CrystalSpawns.definitions()[0]
        val worldSpawnBefore = overworld.respawnData
        mod.player.teleportTo(overworld, 40.3, 90.0, 41.7, emptySet(), 181f, 0f, true)

        helper.runAfterDelay(2) {
            try {
                mod.runCommand("setspawn spawn1")
                helper.assertValueEqual(overworld.respawnData.pos().x, 40, "world spawn x")
                helper.assertValueEqual(overworld.respawnData.pos().z, 41, "world spawn z")
                helper.assertValueEqual(CrystalSpawns.definitions()[0].yaw, 180f, "spawn 1 facing")
            } finally {
                CrystalSpawns.define(0, before)
                overworld.respawnData = worldSpawnBefore
                mod.disconnect()
            }
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 100)
    fun onlyStaffInAdminModeCanSetASpawn(helper: GameTestHelper) {
        val server = helper.level.server
        val plain = TestPlayer.join(server, "SetSpawnPlain")
        val before = CrystalSpawns.definitions()[1]

        helper.runAfterDelay(2) {
            val node = server.commands.dispatcher.root.getChild("setspawn")
            helper.assertTrue(node != null && !node.canUse(plain.player.createCommandSourceStack()), "/setspawn is hidden from ordinary players")
            plain.runCommand("setspawn spawn2")
            helper.assertTrue(CrystalSpawns.definitions()[1] == before, "an ordinary player changed a spawn")
            helper.assertTrue(server.commands.dispatcher.root.getChild("setworldspawn") == null, "/setworldspawn is replaced")
            plain.disconnect()
            helper.succeed()
        }
    }
}
