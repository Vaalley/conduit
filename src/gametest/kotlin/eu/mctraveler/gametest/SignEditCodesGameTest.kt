package eu.mctraveler.gametest

import eu.mctraveler.text.Markdown
import eu.mctraveler.text.SignEditCodes
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.NbtOps
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.entity.SignTextSlot

/** A coloured sign opens in the editor with its colour codes still written out. */
class SignEditCodesGameTest {

    private fun colouredSign(helper: GameTestHelper): SignBlockEntity {
        helper.setBlock(BlockPos(1, 2, 1), Blocks.OAK_SIGN)
        val sign = helper.level.getBlockEntity(helper.absolutePos(BlockPos(1, 2, 1))) as SignBlockEntity
        sign.updateText(
            { text ->
                SignText(
                    listOf(Markdown.parse("%aGreen%r plain"), Markdown.parse("no colour"), Markdown.parse("%l%cBold red"), Markdown.parse("")),
                    listOf(Markdown.parse("%aGreen%r plain"), Markdown.parse("no colour"), Markdown.parse("%l%cBold red"), Markdown.parse("")),
                    text.color,
                    text.hasGlowingText(),
                )
            },
            SignTextSlot.FRONT,
        )
        return sign
    }

    @GameTest
    fun opensWithTheCodesStillInPlace(helper: GameTestHelper) {
        val sign = colouredSign(helper)
        val viewer = TestPlayer.join(helper.level.server, "SignCodesEditor")
        try {
            PacketCapture.drain(viewer.player)
            SignEditCodes.beforeEditorOpens(viewer.player, ClientboundOpenSignEditorPacket(sign.blockPos, SignTextSlot.FRONT), canColour = true)
            val update = PacketCapture.drainOf<ClientboundBlockEntityDataPacket>(viewer.player).single { it.pos == sign.blockPos }
            val ops = helper.level.registryAccess().createSerializationContext(NbtOps.INSTANCE)
            val shown = SignText.CODEC.parse(ops, update.tag.get("front_text")).getOrThrow()
            helper.assertValueEqual(
                shown.getMessages(false).map { it.string },
                listOf("%aGreen%r plain", "no colour", "%c%lBold red", ""),
                "the lines the editor shows",
            )
            // The sign in the world is untouched: still coloured, no codes.
            helper.assertValueEqual(sign.getText(SignTextSlot.FRONT).getMessages(false)[0].string, "Green plain", "the stored sign")
            helper.succeed()
        } finally {
            viewer.disconnect()
        }
    }

    @GameTest
    fun aSignWithNoColourIsLeftAlone(helper: GameTestHelper) {
        helper.setBlock(BlockPos(1, 2, 1), Blocks.OAK_SIGN)
        val blank = helper.level.getBlockEntity(helper.absolutePos(BlockPos(1, 2, 1))) as SignBlockEntity
        val plain = blank.getText(SignTextSlot.FRONT)
        helper.assertTrue(SignEditCodes.withCodes(plain) == null, "an uncoloured sign needs no codes version")
        helper.succeed()
    }

    @GameTest
    fun nothingIsSentWhenColouringIsNotAllowed(helper: GameTestHelper) {
        val sign = colouredSign(helper)
        val viewer = TestPlayer.join(helper.level.server, "SignCodesPlain")
        try {
            PacketCapture.drain(viewer.player)
            SignEditCodes.beforeEditorOpens(viewer.player, ClientboundOpenSignEditorPacket(sign.blockPos, SignTextSlot.FRONT), canColour = false)
            helper.assertTrue(PacketCapture.drainOf<ClientboundBlockEntityDataPacket>(viewer.player).none { it.pos == sign.blockPos }, "a player who cannot colour was sent codes")
            helper.succeed()
        } finally {
            viewer.disconnect()
        }
    }
}
