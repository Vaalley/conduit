package eu.mctraveler.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.util.ProblemReporter
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.storage.TagValueInput

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
        sign.setAllowedPlayerEditor(player.uuid)
        player.writesOnSign(helper, true, "Hanging")

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
    fun reopeningAStyledSignKeepsItsMarkup(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15RoundTrip")
        val sign = helper.placeEditableSign(player)

        player.writesOnSign(helper, true, "<red>One", "<blue>Two")
        sign.setAllowedPlayerEditor(player.uuid)
        player.writesOnSign(helper, true, "One", "Two")

        helper.assertValueEqual(
            sign.frontText.getMessage(0, false).style.color?.value ?: -1,
            0xff5555,
            "the first untouched line color",
        )
        helper.assertValueEqual(
            sign.frontText.getMessage(1, false).style.color?.value ?: -1,
            0x5555ff,
            "the second untouched line color",
        )
        helper.assertValueEqual(
            sign.frontText.getMessage(0, true).style.color?.value ?: -1,
            0xff5555,
            "the first filtered line color",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun retypingOneLineLeavesTheOtherMarkupIntact(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15Retype")
        val sign = helper.placeEditableSign(player)

        player.writesOnSign(helper, true, "<red>One", "<blue>Two")
        sign.setAllowedPlayerEditor(player.uuid)
        player.writesOnSign(helper, true, "New", "Two")

        helper.assertTrue(
            sign.frontText.getMessage(0, false).style.color == null,
            "the retyped line is plain",
        )
        helper.assertValueEqual(
            sign.frontText.getMessage(1, false).style.color?.value ?: -1,
            0x5555ff,
            "the untouched line keeps its color",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun clearingAStoredLineMakesItPlain(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15ClearSource")
        val sign = helper.placeEditableSign(player)

        player.writesOnSign(helper, true, "<red>Stored")
        sign.setAllowedPlayerEditor(player.uuid)
        player.writesOnSign(helper, true, "")

        helper.assertValueEqual(sign.frontText.getMessage(0, false).string, "", "the cleared line")
        helper.assertTrue(
            sign.frontText.getMessage(0, false).style.color == null,
            "the cleared line is plain",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun frontAndBackSourcesRoundTripIndependently(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15Faces")
        val sign = helper.placeEditableSign(player)

        player.writesOnSign(helper, true, "<red>Front")
        sign.setAllowedPlayerEditor(player.uuid)
        player.writesOnSign(helper, false, "<blue>Back")
        sign.setAllowedPlayerEditor(player.uuid)
        player.writesOnSign(helper, true, "Front")
        sign.setAllowedPlayerEditor(player.uuid)
        player.writesOnSign(helper, false, "Back")

        helper.assertValueEqual(
            sign.frontText.getMessage(0, false).style.color?.value ?: -1,
            0xff5555,
            "the front source color",
        )
        helper.assertValueEqual(
            sign.backText.getMessage(0, false).style.color?.value ?: -1,
            0x5555ff,
            "the back source color",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun sourceSurvivesBlockEntitySerialization(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15Save")
        val sign = helper.placeEditableSign(player)
        player.writesOnSign(helper, true, "<red>Saved")

        val saved = sign.saveWithoutMetadata(helper.level.registryAccess())
        val update = sign.getUpdateTag(helper.level.registryAccess())
        helper.assertTrue(saved.contains("mctraveler:sign_sources"), "the saved source field")
        helper.assertTrue(update.contains("mctraveler:sign_sources"), "the update source field")

        val restored = helper.placeEditableSign(player, at = BlockPos(2, 2, 1))
        restored.loadCustomOnly(
            TagValueInput.create(
                ProblemReporter.DISCARDING,
                helper.level.registryAccess(),
                saved,
            ),
        )
        helper.assertValueEqual(
            restored.frontText.getMessage(0, false).style.color?.value ?: -1,
            0xff5555,
            "the restored source color",
        )
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun aPlainSignStoresNoSourceField(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15PlainSave")
        val sign = helper.placeEditableSign(player)

        val saved = sign.saveWithoutMetadata(helper.level.registryAccess())
        helper.assertFalse(saved.contains("mctraveler:sign_sources"), "the plain sign source field")
        player.leave()
        helper.succeed()
    }

    @GameTest
    fun loadingWithoutSourceDataLeavesVanillaText(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T15MissingSource")
        val sign = helper.placeEditableSign(player)
        sign.setText(sign.frontText.setMessage(0, Component.literal("Legacy")), true)
        val saved = sign.saveWithoutMetadata(helper.level.registryAccess())

        val restored = helper.placeEditableSign(player, at = BlockPos(2, 2, 1))
        restored.loadCustomOnly(
            TagValueInput.create(
                ProblemReporter.DISCARDING,
                helper.level.registryAccess(),
                saved,
            ),
        )
        helper.assertValueEqual(
            restored.frontText.getMessage(0, false).string,
            "Legacy",
            "the vanilla text without source data",
        )
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

        player.connection.handleSignUpdate(
            ServerboundSignUpdatePacket(
                helper.absolutePos(SIGN_EFFECTS_AT),
                true,
                "<gradient:#ff0000>Bad",
                "<gradient:#00ff00>Also bad",
                "",
                "",
            ),
        )

        helper.assertValueEqual(
            sign.frontText.getMessage(0, false).string,
            "<gradient:#ff0000>Bad",
            "the malformed sign text",
        )
        helper.assertValueEqual(
            sign.frontText.getMessage(1, false).string,
            "<gradient:#00ff00>Also bad",
            "the second malformed sign text",
        )
        val problem = "unknown or malformed tag"
        val warning = player.messages.lastOrNull()?.string.orEmpty()
        helper.assertTrue(
            warning.split(problem).size - 1 == 2 &&
                warning.contains("line 1: $problem") &&
                warning.contains("line 2: $problem"),
            "the malformed markup warning reports each line once",
        )
        player.leave()
        helper.succeed()
    }
}

private fun GameTestHelper.placeEditableSign(
    player: MessageCapturingPlayer,
    block: Block = Blocks.OAK_SIGN,
    at: BlockPos = SIGN_EFFECTS_AT,
): SignBlockEntity {
    setBlock(at, block)
    return (level.getBlockEntity(absolutePos(at)) as SignBlockEntity).also {
        it.setAllowedPlayerEditor(player.uuid)
    }
}

private fun MessageCapturingPlayer.writesOnSign(
    helper: GameTestHelper,
    front: Boolean,
    first: String,
    second: String = "",
    at: BlockPos = SIGN_EFFECTS_AT,
) {
    connection.handleSignUpdate(
        ServerboundSignUpdatePacket(
            helper.absolutePos(at),
            front,
            first,
            second,
            "",
            "",
        ),
    )
}
