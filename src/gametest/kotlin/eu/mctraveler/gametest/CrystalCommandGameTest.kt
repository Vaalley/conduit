package eu.mctraveler.gametest

import eu.mctraveler.crystal.CrystalEnergy
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.Level

/**
 * `/set-teleportation-crystal-energy <energy> [player]` (spec User Story 37).
 *
 * Seam: the real command dispatcher and the messages the *sender* is shown.
 * Nucleus sent its replies to the target instead, so who receives them is
 * itself under test (deviation 5).
 */
class CrystalCommandGameTest {

    private val command = "set-teleportation-crystal-energy"
    @GameTest
    fun noArgumentsRepliesUsageEvenToANonAdmin(helper: GameTestHelper) {
        // Usage comes before the admin gate (house rule).
        val visitor = MessageCapturingPlayer.join(helper, "CrystalCurious")
        try {
            visitor.runCommand(command)
            helper.assertValueEqual(
                visitor.messages.map { it.string },
                listOf("USAGE /set-teleportation-crystal-energy <energy> [player]"),
                "the reply to the bare command",
            )
            helper.succeed()
        } finally {
            visitor.leave()
        }
    }

    @GameTest
    fun aNonAdminIsRefused(helper: GameTestHelper) {
        val visitor = MessageCapturingPlayer.join(helper, "CrystalPretender")
        try {
            visitor.runCommand("$command 0")
            helper.assertValueEqual(
                visitor.messages.map { it.string },
                listOf("ERROR You must be an admin to use this command"),
                "the reply to a non-admin",
            )
            helper.assertValueEqual(CrystalEnergy.energyOf(visitor), 5, "a refused command changes nothing")
            helper.succeed()
        } finally {
            visitor.leave()
        }
    }

    @GameTest
    fun energyOutOfRangeIsRefused(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "CrystalBoss1")
        try {
            admin.makeAdmin()
            for (energy in listOf(6, -1)) {
                admin.messages.clear()
                admin.runCommand("$command $energy")
                helper.assertValueEqual(
                    admin.messages.map { it.string },
                    listOf("ERROR Energy must be between 0 and 5"),
                    "the reply to energy $energy",
                )
            }
            helper.succeed()
        } finally {
            admin.leave()
        }
    }

    @GameTest
    fun anUnknownPlayerIsReportedInRed(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "CrystalBoss2")
        try {
            admin.makeAdmin()
            admin.runCommand("$command 1 Nobody")
            val reply = admin.messages.single()
            helper.assertValueEqual(reply.string, "ERROR Nobody is not online", "the unknown-player reply")
            helper.assertTrue(
                runsOf(reply).any { it.text == "Nobody" && it.color == "red" },
                "the unknown player's name should be red, got ${runsOf(reply)}",
            )
            helper.succeed()
        } finally {
            admin.leave()
        }
    }

    @GameTest
    fun settingOwnEnergyConfirmsInGreen(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "CrystalBoss3")
        try {
            admin.makeAdmin()
            admin.runCommand("$command 1")
            val reply = admin.messages.single()
            helper.assertValueEqual(
                reply.string,
                "SUCCESS CrystalBoss3 now has 1 energy",
                "the success reply",
            )
            val runs = runsOf(reply)
            helper.assertTrue(
                runs.any { it.text == "CrystalBoss3" && it.color == "green" },
                "the target's name should be green, got $runs",
            )
            helper.assertTrue(
                runs.any { it.text == "1" && it.color == "green" },
                "the energy count should be green, got $runs",
            )
            helper.assertValueEqual(CrystalEnergy.energyOf(admin), 1, "the energy actually set")
            helper.succeed()
        } finally {
            admin.leave()
        }
    }

    @GameTest
    fun settingAnotherPlayersEnergyTellsTheSenderNotTheTarget(helper: GameTestHelper) {
        // Nucleus told the target and left the sender staring at nothing; the
        // message names the target in the third person, so it was always the
        // sender's (deviation 5).
        val admin = MessageCapturingPlayer.join(helper, "CrystalBoss4")
        val target = MessageCapturingPlayer.join(helper, "CrystalSubject")
        try {
            admin.makeAdmin()
            admin.messages.clear()
            target.messages.clear()
            admin.runCommand("$command 0 CrystalSubject")
            helper.assertValueEqual(
                admin.messages.map { it.string },
                listOf("SUCCESS CrystalSubject now has 0 energy"),
                "the sender's reply",
            )
            helper.assertValueEqual(
                target.messages.map { it.string },
                emptyList(),
                "the target should be told nothing",
            )
            helper.assertValueEqual(CrystalEnergy.energyOf(target), 0, "the target's energy")
            helper.assertValueEqual(CrystalEnergy.energyOf(admin), 5, "the sender's own energy is untouched")
            helper.succeed()
        } finally {
            admin.leave()
            target.leave()
        }
    }

    @GameTest
    fun spawnCommandsTeleportForFree(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "CrystalSpawnCommands")
        try {
            CrystalEnergy.setEnergy(player, 2)
            player.runCommand("spawn1")
            helper.assertValueEqual(player.x, 16.5, "spawn1 x")
            helper.assertValueEqual(player.y, 71.0, "spawn1 y")
            helper.assertValueEqual(player.z, -15.5, "spawn1 z")
            helper.assertValueEqual(player.yRot, 180.0f, "spawn1 yaw")
            helper.assertValueEqual(CrystalEnergy.energyOf(player), 2, "energy after spawn1")
            helper.assertValueEqual(player.messages.last().string, "SUCCESS Arrived at spawn 1", "spawn1 reply")

            player.runCommand("spawn2")
            helper.assertValueEqual(
                player.level().dimension(),
                Level.OVERWORLD,
                "spawn2 dimension",
            )
            helper.assertValueEqual(player.x, 0.5, "spawn2 x")
            helper.assertValueEqual(player.y, 67.5, "spawn2 y")
            helper.assertValueEqual(player.z, 802816.5, "spawn2 z")
            helper.assertValueEqual(player.yRot, 0.0f, "spawn2 yaw")
            helper.assertValueEqual(CrystalEnergy.energyOf(player), 2, "energy after spawn2")
            helper.assertValueEqual(player.messages.last().string, "SUCCESS Arrived at spawn 2", "spawn2 reply")
            helper.succeed()
        } finally {
            player.leave()
        }
    }
}
