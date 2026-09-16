package eu.mctraveler.rtp

import eu.mctraveler.text.Paint
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.entity.SignTextSlot

/**
 * Signs that `/rtp` a player who right-clicks them — the way a new arrival at
 * spawn gets sent out without knowing a command exists.
 *
 * The mark is carried by the sign itself, in the `insertion` of its first front
 * line's style: it round-trips through sign NBT, is invisible to players, and
 * goes away with the sign when the sign is broken, so there is no registry to
 * keep in step with the world. The same trick [eu.mctraveler.text.SignNames]
 * uses for `<name>`, with a different sentinel.
 *
 * A marked sign is waxed, so nobody can edit the mark away — or edit it in.
 */
object RtpSigns {

    const val SENTINEL = "mctraveler:rtp-sign"

    /** What a blank sign is made to say when it is marked. */
    val DEFAULT_LINES: List<Component> = listOf(
        Paint.bold("[ Random ]"),
        Paint.bold("[ Teleport ]"),
        Component.empty(),
        Paint.darkGray("right-click me"),
    )

    fun isMarked(sign: SignBlockEntity): Boolean = isMarked(sign.getText(SignTextSlot.FRONT))

    fun isMarked(text: SignText): Boolean = text.getMessages(false)[0].style.insertion == SENTINEL

    /**
     * Marks [sign], filling in [DEFAULT_LINES] if it says nothing yet, and
     * waxes it. Returns false when it was already marked.
     */
    fun mark(sign: SignBlockEntity): Boolean {
        if (isMarked(sign)) return false
        sign.updateText({ text -> mark(text) }, SignTextSlot.FRONT)
        sign.setWaxed(true)
        return true
    }

    /** Undoes [mark]; the sign stays waxed. Returns false when it was not marked. */
    fun unmark(sign: SignBlockEntity): Boolean {
        if (!isMarked(sign)) return false
        sign.updateText({ text -> unmark(text) }, SignTextSlot.FRONT)
        return true
    }

    fun mark(text: SignText): SignText {
        val filled = if (isBlank(text)) {
            DEFAULT_LINES.foldIndexed(text) { i, acc, line -> acc.withLine(i, line) }
        } else {
            text
        }
        val first = filled.getMessages(false)[0]
        return filled.withLine(0, first.copy().withStyle { it.withInsertion(SENTINEL) })
    }

    fun unmark(text: SignText): SignText {
        val first = text.getMessages(false)[0]
        return text.withLine(0, first.copy().withStyle { it.withInsertion(null) })
    }

    /** [SignText.setMessage] is gone: rebuild the immutable text with line [i] replaced. */
    private fun SignText.withLine(i: Int, line: Component): SignText = SignText(
        getMessages(false).toMutableList().also { it[i] = line },
        getMessages(true).toMutableList().also { it[i] = line },
        color,
        hasGlowingText(),
    )

    private fun isBlank(text: SignText): Boolean =
        text.getMessages(false).all { it.string.isBlank() }
}
