package eu.mctraveler.text

import eu.mctraveler.MCTraveler
import java.util.function.BiFunction
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.NbtOps
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.entity.SignTextSlot

/**
 * When someone who can colour signs opens a sign to edit it, the editor shows the `%` codes
 * they typed (`%aGreen`) and not the bare coloured word, so editing a coloured sign does not
 * strip its colours.
 *
 * The edit screen reads the sign as the client holds it, so just before the editor opens the
 * player is sent the sign with its colours written back out as codes. Saving the edit (or
 * closing it) sends the lines back, which are coloured again and broadcast as usual.
 */
object SignEditCodes {

    /** Set while the codes version is being sent, so other sign rewrites leave that packet alone. */
    private val sending = ThreadLocal<Boolean>()

    /** Whether the packet being sent right now is the codes version of a sign. */
    fun isSendingCodes(): Boolean = sending.get() == true

    /** Called for the packet that opens the editor on [viewer]'s screen. */
    fun beforeEditorOpens(viewer: ServerPlayer, packet: ClientboundOpenSignEditorPacket, canColour: Boolean) {
        if (!canColour) return
        val sign = viewer.level().getBlockEntity(packet.pos()) as? SignBlockEntity ?: return
        val shown = sign.getText(packet.slot())
        val codes = withCodes(shown) ?: return
        val side = if (packet.slot() == SignTextSlot.FRONT) "front_text" else "back_text"
        val update = ClientboundBlockEntityDataPacket.create(
            sign,
            BiFunction { blockEntity, registryAccess: RegistryAccess ->
                val tag = blockEntity.getUpdateTag(registryAccess)
                val ops = registryAccess.createSerializationContext(NbtOps.INSTANCE)
                SignText.CODEC.encodeStart(ops, codes).result().ifPresentOrElse(
                    { tag.put(side, it) },
                    { MCTraveler.LOGGER.warn("Could not encode the colour codes of a sign for {}", viewer.gameProfile.name) },
                )
                tag
            },
        )
        sending.set(true)
        try {
            viewer.connection.send(update)
        } finally {
            sending.remove()
        }
    }

    /** [text] with each coloured line written back as `%` codes, or null when no line has any colour. */
    fun withCodes(text: SignText): SignText? {
        val raw = text.getMessages(false)
        val lines = raw.map { Component.literal(Markdown.unparse(it)) }
        if (lines.indices.all { lines[it].string == raw[it].string }) return null
        return SignText(lines, lines, text.color, text.hasGlowingText())
    }
}
