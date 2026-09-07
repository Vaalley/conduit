package eu.mctraveler.gametest

import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.rtp.RtpConfig
import eu.mctraveler.rtp.RtpCooldown
import eu.mctraveler.rtp.RtpSigns
import eu.mctraveler.worlds.TeleportCountdown
import kotlin.math.hypot
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * `/rtp`, `/rtp sign`, and the countdown every teleport now goes through, at
 * the running-server seam: the real dispatcher, the real tick loop, the real
 * chunk generation out in the ring.
 *
 * The gametest world is flat, so every column out there is safe ground; what
 * is under test is the flow around the pick, not the pick's judgement of
 * terrain (that is `RtpPickerTest`).
 */
class RtpGameTest {

    private val afterCountdown = TeleportCountdown.DURATION_TICKS + 2L

    @GameTest(maxTicks = 200)
    fun rtpCountsDownThenLandsFarFromSpawnAndStartsTheCooldown(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "RtpRider")
        RtpCooldown.forget(player.uuid)
        player.standAt(helper, 1.0, 1.0, 1.0)
        val start = player.position()
        player.messages.clear()

        player.runCommand("rtp")
        helper.assertValueEqual(
            player.actionBarMessages.map { it.string },
            listOf("Teleporting in 3..."),
            "the count begins at three",
        )
        helper.assertTrue(player.position() == start, "the player left before the count was over")

        helper.runAfterDelay(afterCountdown) {
            try {
                helper.assertValueEqual(
                    player.actionBarMessages.map { it.string },
                    listOf("Teleporting in 3...", "Teleporting in 2...", "Teleporting in 1...", ""),
                    "the count spoken to the player",
                )
                val spawn = helper.level.server.overworld().respawnData.pos()
                val distance = hypot(player.x - spawn.x, player.z - spawn.z)
                val settings = RtpConfig.settings()
                helper.assertTrue(
                    distance >= settings.minDistance - 1 && distance <= settings.radius + 1,
                    "landed $distance from spawn, outside ${settings.minDistance}..${settings.radius}",
                )
                helper.assertTrue(
                    helper.level.getBlockState(player.blockPosition().below()).isSolid,
                    "landed on ${helper.level.getBlockState(player.blockPosition().below())}",
                )
                helper.assertValueEqual(
                    player.replies(),
                    listOf("SUCCESS Teleported to ${player.blockPosition().x}, ${player.blockPosition().z}"),
                    "the arrival reply",
                )

                player.chatMessages.clear()
                player.runCommand("rtp")
                helper.assertValueEqual(
                    player.replies(),
                    listOf("ERROR You can use /rtp again in 5m"),
                    "the reply during the cooldown",
                )
                helper.succeed()
            } finally {
                RtpCooldown.forget(player.uuid)
                player.leave()
            }
        }
    }

    @GameTest(maxTicks = 200)
    fun movingDuringTheCountCancelsIt(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "RtpFidget")
        RtpCooldown.forget(player.uuid)
        player.standAt(helper, 1.0, 1.0, 1.0)
        player.messages.clear()

        player.runCommand("rtp")
        helper.runAfterDelay(5) { player.standAt(helper, 3.0, 1.0, 1.0) }
        helper.runAfterDelay(afterCountdown) {
            try {
                helper.assertValueEqual(
                    player.actionBarMessages.map { it.string },
                    listOf("Teleporting in 3...", "Teleport cancelled: you moved"),
                    "what the player was shown",
                )
                helper.assertValueEqual(player.replies(), emptyList(), "no arrival was reported")
                helper.assertValueEqual(player.position(), helper.absoluteVec(Vec3(3.0, 1.0, 1.0)), "the player's position")
                helper.assertValueEqual(RtpCooldown.remaining(player.uuid, helper.level.server.tickCount), 0, "a cancelled trip costs no cooldown")
                helper.succeed()
            } finally {
                player.leave()
            }
        }
    }

    @GameTest
    fun rtpIsRefusedOutsideTheOverworld(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "RtpAbroad")
        val embassies = helper.level.server.getLevel(EmbassiesFeature.DIMENSION)!!
        try {
            check(player.teleportTo(embassies, 0.5, 1.0, 0.5, emptySet(), 0.0f, 0.0f, false))
            player.messages.clear()
            player.runCommand("rtp")
            helper.assertValueEqual(
                player.replies(),
                listOf("ERROR Random teleport only works in the overworld"),
                "the reply from the Embassies",
            )
            helper.assertTrue(!TeleportCountdown.isCounting(player.uuid), "a refused rtp started a count")
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun anAdminSkipsTheCooldown(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "RtpBoss")
        try {
            admin.makeAdmin()
            RtpCooldown.start(admin.uuid, helper.level.server.tickCount, 6000)
            admin.messages.clear()
            admin.runCommand("rtp")
            helper.assertValueEqual(
                admin.actionBarMessages.map { it.string },
                listOf("Teleporting in 3..."),
                "an admin on cooldown still counts down",
            )
            helper.succeed()
        } finally {
            TeleportCountdown.forget(admin.uuid)
            RtpCooldown.forget(admin.uuid)
            admin.leave()
        }
    }

    @GameTest
    fun aSecondRtpDuringTheCountIsRefused(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "RtpEager")
        try {
            RtpCooldown.forget(player.uuid)
            player.runCommand("rtp")
            player.messages.clear()
            player.runCommand("rtp")
            helper.assertValueEqual(
                player.replies(),
                listOf("ERROR You are already teleporting"),
                "the reply to a second rtp",
            )
            helper.succeed()
        } finally {
            TeleportCountdown.forget(player.uuid)
            player.leave()
        }
    }

    // ---- /rtp sign ----

    @GameTest
    fun aNonAdminMayNotMakeSigns(helper: GameTestHelper) {
        val visitor = MessageCapturingPlayer.join(helper, "RtpSignless")
        try {
            val sign = helper.placeSign()
            visitor.looksAt(helper, SIGN_AT)
            visitor.messages.clear()
            visitor.runCommand("rtp sign")
            helper.assertValueEqual(
                visitor.replies(),
                listOf("ERROR You must be an admin to use this command"),
                "the reply to a non-admin",
            )
            helper.assertTrue(!RtpSigns.isMarked(sign), "the sign was marked anyway")
            helper.succeed()
        } finally {
            visitor.leave()
        }
    }

    @GameTest
    fun anAdminLookingAtNothingIsToldToLookAtASign(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "RtpSignBlind")
        try {
            admin.makeAdmin()
            admin.standAt(helper, 1.0, 1.0, 1.0)
            admin.lookAt(EntityAnchorArgument.Anchor.EYES, helper.absoluteVec(Vec3(1.0, 30.0, 1.0)))
            admin.messages.clear()
            admin.runCommand("rtp sign")
            helper.assertValueEqual(
                admin.replies(),
                listOf("ERROR Look at a sign to make it a random teleport sign"),
                "the reply when no sign is in view",
            )
            helper.succeed()
        } finally {
            admin.leave()
        }
    }

    @GameTest
    fun anAdminMarksTheSignTheyLookAtAndMarksItBackAgain(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "RtpSignSmith")
        try {
            admin.makeAdmin()
            val sign = helper.placeSign()
            admin.looksAt(helper, SIGN_AT)
            admin.messages.clear()

            admin.runCommand("rtp sign")
            helper.assertValueEqual(
                admin.replies(),
                listOf("SUCCESS This sign now teleports players randomly"),
                "the reply to marking",
            )
            helper.assertTrue(RtpSigns.isMarked(sign), "the sign is not marked")
            helper.assertTrue(sign.isWaxed, "the sign is not waxed")
            helper.assertValueEqual(
                (0 until 4).map { sign.frontText.getMessage(it, false).string },
                listOf("[ Random ]", "[ Teleport ]", "", "right-click me"),
                "the words on a blank sign once marked",
            )

            admin.chatMessages.clear()
            admin.runCommand("rtp sign")
            helper.assertValueEqual(
                admin.replies(),
                listOf("SUCCESS This sign is a plain sign again"),
                "the reply to unmarking",
            )
            helper.assertTrue(!RtpSigns.isMarked(sign), "the sign is still marked")
            helper.succeed()
        } finally {
            admin.leave()
        }
    }

    @GameTest
    fun aSignThatAlreadySaysSomethingKeepsItsWords(helper: GameTestHelper) {
        val admin = MessageCapturingPlayer.join(helper, "RtpSignScribe")
        try {
            admin.makeAdmin()
            val sign = helper.placeSign()
            sign.updateText({ it.setMessage(0, net.minecraft.network.chat.Component.literal("Go explore!")) }, true)
            admin.looksAt(helper, SIGN_AT)
            admin.runCommand("rtp sign")
            helper.assertTrue(RtpSigns.isMarked(sign), "the sign is not marked")
            helper.assertValueEqual(sign.frontText.getMessage(0, false).string, "Go explore!", "the first line")
            helper.succeed()
        } finally {
            admin.leave()
        }
    }

    @GameTest(maxTicks = 200)
    fun rightClickingAMarkedSignStartsAnRtp(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "RtpSignUser")
        RtpCooldown.forget(player.uuid)
        val sign = helper.placeSign()
        RtpSigns.mark(sign)
        player.standAt(helper, 1.0, 1.0, 1.0)
        player.messages.clear()

        val result = player.rightClicks(helper, SIGN_AT)
        helper.assertValueEqual(result, InteractionResult.SUCCESS_SERVER, "the click on the sign")
        helper.assertValueEqual(
            player.actionBarMessages.map { it.string },
            listOf("Teleporting in 3..."),
            "the count begins on the click",
        )
        helper.runAfterDelay(afterCountdown) {
            try {
                val spawn = helper.level.server.overworld().respawnData.pos()
                val distance = hypot(player.x - spawn.x, player.z - spawn.z)
                helper.assertTrue(
                    distance >= RtpConfig.settings().minDistance - 1,
                    "the sign sent the player only $distance from spawn",
                )
                helper.succeed()
            } finally {
                RtpCooldown.forget(player.uuid)
                player.leave()
            }
        }
    }

    @GameTest
    fun rightClickingAPlainSignDoesNothingOfTheSort(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "RtpSignReader")
        try {
            helper.placeSign()
            player.standAt(helper, 1.0, 1.0, 1.0)
            player.messages.clear()
            player.rightClicks(helper, SIGN_AT)
            helper.assertTrue(!TeleportCountdown.isCounting(player.uuid), "a plain sign started a count")
            helper.assertValueEqual(player.actionBarMessages.map { it.string }, emptyList(), "the action bar")
            helper.succeed()
        } finally {
            player.leave()
        }
    }
}

private val SIGN_AT = BlockPos(3, 2, 1)

/**
 * The chat lines addressed to this player, less the broadcasts every test
 * player overhears while the batch runs (joins, other tests' verdicts, stamps).
 */
private fun MessageCapturingPlayer.replies(): List<String> =
    chatMessages.map { it.string }.filter { line -> PREFIXES.any(line::startsWith) }

private val PREFIXES = listOf("SUCCESS ", "ERROR ")

private fun GameTestHelper.placeSign(): SignBlockEntity {
    setBlock(SIGN_AT.below(), Blocks.STONE)
    setBlock(SIGN_AT, Blocks.OAK_SIGN)
    return level.getBlockEntity(absolutePos(SIGN_AT)) as SignBlockEntity
}

/** Stands two blocks from [target] at eye height, facing it. */
private fun MessageCapturingPlayer.looksAt(helper: GameTestHelper, target: BlockPos) {
    standAt(helper, target.x + 0.5, target.y - 1.0, target.z + 2.5)
    lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(helper.absolutePos(target)))
}

/** Right-clicks [at] with an empty main hand, as a client does. */
private fun MessageCapturingPlayer.rightClicks(helper: GameTestHelper, at: BlockPos): InteractionResult {
    setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
    val target = helper.absolutePos(at)
    val hit = BlockHitResult(Vec3.atCenterOf(target), Direction.SOUTH, target, false)
    return gameMode.useItemOn(this, level(), mainHandItem, InteractionHand.MAIN_HAND, hit)
}
