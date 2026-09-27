package eu.mctraveler.gametest

import net.minecraft.gametest.framework.GameTestHelper

/**
 * A command gated by an audience (`requires`) is not a command at all to anyone
 * outside it: running it is vanilla's unknown-command failure, not the old in-body
 * "You must be an admin" refusal.
 */
fun GameTestHelper.assertUnknownCommand(player: MessageCapturingPlayer, what: String) {
    val texts = player.messages.map { it.string }
    assertTrue(texts.isNotEmpty(), "$what: the player was told nothing")
    assertTrue(
        texts.any { it.contains("nknown", ignoreCase = true) },
        "$what: expected the unknown-command failure, got $texts",
    )
    assertTrue(texts.none { it.contains("must be an admin") }, "$what: got the in-body refusal: $texts")
}
