package eu.mctraveler.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SignBlockEntity

private val SIGN_EFFECTS_AT = BlockPos(1, 2, 1)

class SignEffectsGameTest {

    @GameTest
    fun aMarkupEffectRendersOnTheFrontFace(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15Front")
        val sign = helper.placeEditableSign(player)

        player.writesOnSign(helper, true, "<red>Hello")

        val message = sign.frontText.getMessage(0, false)
        helper.assertValueEqual(message.string, "Hello", "the rendered sign text")
        helper.assertValueEqual(message.style.color?.value ?: -1, 0xff5555, "the rendered sign color")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun aMarkupEffectRendersOnTheBackFace(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15Back")
        val sign = helper.placeEditableSign(player)

        player.writesOnSign(helper, false, "<blue>Back")

        val message = sign.backText.getMessage(0, false)
        helper.assertValueEqual(message.string, "Back", "the rendered back-sign text")
        helper.assertValueEqual(message.style.color?.value ?: -1, 0x5555ff, "the rendered back-sign color")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun aHangingSignUsesTheSameRenderingPath(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15Hang")
        val sign = helper.placeEditableSign(player, Blocks.OAK_HANGING_SIGN)

        player.writesOnSign(helper, true, "<green>Hanging")

        val message = sign.frontText.getMessage(0, false)
        helper.assertValueEqual(message.string, "Hanging", "the rendered hanging-sign text")
        helper.assertValueEqual(message.style.color?.value ?: -1, 0x55ff55, "the rendered hanging-sign color")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun rawAndFilteredVariantsBothReceiveEffects(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15Filter")
        val sign = helper.placeEditableSign(player)

        player.writesOnSign(helper, true, "<yellow>Filtered")

        helper.assertValueEqual(
            sign.frontText.getMessage(0, false).style.color?.value ?: -1,
            0xffff55,
            "the raw variant color",
        )
        helper.assertValueEqual(
            sign.frontText.getMessage(0, true).style.color?.value ?: -1,
            0xffff55,
            "the filtered variant color",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun replacingMarkupWithPlainTextClearsThePreviousStyle(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15Clear")
        val sign = helper.placeEditableSign(player)

        player.writesOnSign(helper, true, "<red>Styled")
        sign.setAllowedPlayerEditor(player.uuid)
        player.writesOnSign(helper, true, "Plain")

        val message = sign.frontText.getMessage(0, false)
        helper.assertValueEqual(message.string, "Plain", "the replacement sign text")
        helper.assertTrue(message.style.color == null, "the previous sign color was cleared")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun regionProtectionStillRefusesForeignSignEdits(helper: GameTestHelper) {
        val owner = MessageCapturingPlayer.join(helper, "T15Owner")
        val visitor = MessageCapturingPlayer.join(helper, "T15Visitor")
        createRegion(helper, owner, 0.0 to 0.0, 4.0 to 4.0)
        val sign = helper.placeEditableSign(owner)

        visitor.connection.handleSignUpdate(
            ServerboundSignUpdatePacket(
                helper.absolutePos(SIGN_EFFECTS_AT),
                true,
                "<red>Blocked",
                "",
                "",
                "",
            ),
        )

        helper.assertValueEqual(sign.frontText.getMessage(0, false).string, "", "the protected sign text")
        helper.assertValueEqual(
            visitor.messages.last(),
            protectedBy("T15Owner's Place"),
            "the foreign sign-edit refusal",
        )
        owner.leave()
        visitor.leave()
        helper.succeed()
    }

    @GameTest
    fun aWaxedSignStillRefusesEdits(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15Waxed")
        val sign = helper.placeEditableSign(player)
        sign.setWaxed(true)

        player.writesOnSign(helper, true, "<red>Blocked")

        helper.assertValueEqual(sign.frontText.getMessage(0, false).string, "", "the waxed sign text")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun dyeColourAndGlowInkSurviveMarkup(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15Glow")
        val sign = helper.placeEditableSign(player)
        sign.setText(sign.frontText.setColor(DyeColor.BLUE).setHasGlowingText(true), true)
        sign.setAllowedPlayerEditor(player.uuid)

        player.writesOnSign(helper, true, "<red>Glowing")

        helper.assertValueEqual(sign.frontText.color, DyeColor.BLUE, "the sign dye color")
        helper.assertTrue(sign.frontText.hasGlowingText(), "the sign glow-ink state")
        helper.assertValueEqual(sign.frontText.getMessage(0, false).string, "Glowing", "the glowing sign text")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun malformedMarkupKeepsTextAndWarnsThePlayer(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15Bad")
        val sign = helper.placeEditableSign(player)

        player.writesOnSign(helper, true, "<gradient:#ff0000>Bad")

        helper.assertValueEqual(
            sign.frontText.getMessage(0, false).string,
            "<gradient:#ff0000>Bad",
            "the malformed sign text",
        )
        helper.assertTrue(
            player.messages.lastOrNull()?.string?.contains("unknown or malformed tag") == true,
            "the malformed markup warning",
        )
        player.leave()
        helper.succeed()
    }
}

private fun GameTestHelper.placeEditableSign(
    player: MessageCapturingPlayer,
    block: Block = Blocks.OAK_SIGN,
): SignBlockEntity {
    setBlock(SIGN_EFFECTS_AT, block)
    return (level.getBlockEntity(absolutePos(SIGN_EFFECTS_AT)) as SignBlockEntity).also {
        it.setAllowedPlayerEditor(player.uuid)
    }
}

private fun MessageCapturingPlayer.writesOnSign(
    helper: GameTestHelper,
    front: Boolean,
    line: String,
) {
    connection.handleSignUpdate(
        ServerboundSignUpdatePacket(
            helper.absolutePos(SIGN_EFFECTS_AT),
            front,
            line,
            "",
            "",
            "",
        ),
    )
}
