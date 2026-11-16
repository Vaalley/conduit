package eu.mctraveler.gametest

import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.dragonfight.DragonFightFeature
import eu.mctraveler.rtp.RtpConfig
import eu.mctraveler.rtp.RtpCooldown
import eu.mctraveler.rtp.RtpKind
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
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * `/rtp`, ``<wilderness>` signs, and the countdown every teleport now goes through, at
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

        player.runCommand("wilderness")
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
                val spawn = eu.mctraveler.crystal.CrystalSpawns.definitions()[1]
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
                player.runCommand("wilderness")
                helper.assertValueEqual(
                    player.replies(),
                    listOf("ERROR You can use /wilderness again in 5m"),
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

        player.runCommand("wilderness")
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
            player.runCommand("wilderness")
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
    fun rtpEndIsRefusedBeforeTheDragonFightIsCompleted(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "RtpEndLocked")
        try {
            player.messages.clear()
            player.chatMessages.clear()
            player.runCommand("wilderness end")
            helper.assertValueEqual(
                player.replies(),
                listOf("ERROR Free the End first (/dragonfight)"),
                "the completion gate",
            )
            helper.assertTrue(!TeleportCountdown.isCounting(player.uuid), "a refused End rtp started a count")
            helper.succeed()
        } finally {
            player.leave()
        }
    }

    @GameTest
    fun completedPlayersCanStartRtpEndCountdown(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "RtpEndUnlocked")
        try {
            DragonFightFeature.state!!.markCompleted(player.uuid)
            player.messages.clear()
            player.chatMessages.clear()
            player.runCommand("wilderness end")
            helper.assertValueEqual(
                player.actionBarMessages.map { it.string },
                listOf("Teleporting in 3..."),
                "the End teleport countdown",
            )
            helper.succeed()
        } finally {
            TeleportCountdown.forget(player.uuid)
            RtpCooldown.forget(player.uuid, RtpKind.END)
            DragonFightFeature.state!!.forgetCompleted(player.uuid)
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
            admin.runCommand("wilderness")
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
            player.runCommand("wilderness")
            player.messages.clear()
            player.runCommand("wilderness")
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

    // ---- <wilderness> signs ----

    @GameTest
    fun aSignSayingWildernessBecomesAWildernessSign(helper: GameTestHelper) {
        val sign = helper.placeSign()
        sign.updateText({ it.asMutable().setLine(0, net.minecraft.network.chat.Component.literal("<wilderness>")).asImmutable() }, SignTextSlot.FRONT)
        RtpSigns.convertToken(helper.level, sign)

        helper.assertTrue(RtpSigns.isMarked(sign), "the sign is not marked")
        helper.assertTrue(sign.isWaxed, "the sign is not waxed")
        helper.assertValueEqual(
            (0 until 4).map { sign.getText(SignTextSlot.FRONT).getMessages(false)[it].string },
            listOf("[Wilderness]", "Press right-click", "to teleport!", ""),
            "the words on the sign",
        )
        val title = sign.getText(SignTextSlot.FRONT).getMessages(false)[0]
        helper.assertTrue(title.siblings.any { it.style.isBold }, "the title is not bold")
        helper.succeed()
    }

    @GameTest
    fun aSignInTheEndSaysTheEnd(helper: GameTestHelper) {
        helper.assertValueEqual(RtpSigns.wildernessLines(inEnd = true)[0].string, "[The End]", "the End sign title")
        helper.assertValueEqual(RtpSigns.wildernessLines(inEnd = false)[0].string, "[Wilderness]", "the overworld sign title")
        helper.succeed()
    }

    @GameTest
    fun anOrdinarySignIsLeftAlone(helper: GameTestHelper) {
        val sign = helper.placeSign()
        sign.updateText({ it.asMutable().setLine(0, net.minecraft.network.chat.Component.literal("Hello")).asImmutable() }, SignTextSlot.FRONT)
        helper.assertTrue(!RtpSigns.convertToken(helper.level, sign), "a plain sign was converted")
        helper.assertTrue(!RtpSigns.isMarked(sign), "a plain sign was marked")
        helper.succeed()
    }

    @GameTest
    fun theSignCommandIsGone(helper: GameTestHelper) {
        val node = helper.level.server.commands.dispatcher.root.getChild("wilderness")
        helper.assertTrue(node != null && node.getChild("sign") == null, "/wilderness sign still exists")
        helper.assertTrue(helper.level.server.commands.dispatcher.root.getChild("rtp") == null, "/rtp still exists")
        helper.succeed()
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
                val spawn = eu.mctraveler.crystal.CrystalSpawns.definitions()[1]
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
