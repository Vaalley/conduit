package eu.mctraveler.gametest

import eu.mctraveler.hooks.Hooks
import eu.mctraveler.rank.Rank
import eu.mctraveler.rank.RankFeature
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.NbtOps
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText

/**
 * The `<name>` sign-markdown token renders as the reading player's own name,
 * per viewer, for Donators and admins only — the same gate the rest of the
 * sign markdown uses.
 */
class SignNameTokenGameTest {

    private companion object {
        val SIGN_AT = BlockPos(2, 2, 2)
    }

    @GameTest(maxTicks = 40)
    fun eachViewerSeesTheirOwnNameWhereADonatorTypedTheToken(helper: GameTestHelper) {
        val donator = MessageCapturingPlayer.join(helper, "SignNameDon")
        RankFeature.setRank(donator, Rank.DONATOR)
        val sign = placeSign(helper, donator)
        writeFirstLine(helper, donator, "Hi <name>!")

        // Baked into the block entity as a sentinel run, invisible literal for anyone unresolved.
        helper.assertValueEqual(sign.frontText.getMessage(0, false).string, "Hi <name>!", "sentinel fallback text")

        val alice = MessageCapturingPlayer.join(helper, "SignNameAlice")
        val bob = MessageCapturingPlayer.join(helper, "SignNameBob")

        helper.assertValueEqual(renderedFirstLine(sign, alice), "Hi SignNameAlice!", "Alice's personalized line")
        helper.assertValueEqual(renderedFirstLine(sign, bob), "Hi SignNameBob!", "Bob's personalized line")

        donator.leave()
        alice.leave()
        bob.leave()
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun aNonPrivilegedEditorsTokenStaysLiteralForEveryone(helper: GameTestHelper) {
        val traveler = MessageCapturingPlayer.join(helper, "SignNameTrav")
        val sign = placeSign(helper, traveler)
        writeFirstLine(helper, traveler, "Hi <name>!")

        helper.assertValueEqual(sign.frontText.getMessage(0, false).string, "Hi <name>!", "literal token text")

        val viewer = MessageCapturingPlayer.join(helper, "SignNameViewer")
        val original: ClientboundBlockEntityDataPacket = sign.updatePacket
        val personalized = Hooks.packetForViewer(viewer, original)
        helper.assertTrue(personalized === original, "a non-Donator's <name> must not be rewritten")

        traveler.leave()
        viewer.leave()
        helper.succeed()
    }

    private fun placeSign(helper: GameTestHelper, editor: ServerPlayer): SignBlockEntity {
        helper.setBlock(SIGN_AT, Blocks.OAK_SIGN)
        val sign = helper.level.getBlockEntity(helper.absolutePos(SIGN_AT)) as SignBlockEntity
        sign.setAllowedPlayerEditor(editor.uuid)
        return sign
    }

    private fun writeFirstLine(helper: GameTestHelper, editor: ServerPlayer, line: String) {
        editor.connection.handleSignUpdate(
            ServerboundSignUpdatePacket(helper.absolutePos(SIGN_AT), true, line, "", "", ""),
        )
    }

    /** The first line of the sign as [viewer] would render it, via the outgoing packet seam. */
    private fun renderedFirstLine(sign: SignBlockEntity, viewer: ServerPlayer): String {
        val packet = Hooks.packetForViewer(viewer, sign.updatePacket) as ClientboundBlockEntityDataPacket
        val ops = viewer.level().registryAccess().createSerializationContext(NbtOps.INSTANCE)
        val front = SignText.DIRECT_CODEC.parse(ops, packet.tag.getCompoundOrEmpty("front_text")).orThrow
        return front.getMessage(0, false).string
    }
}
