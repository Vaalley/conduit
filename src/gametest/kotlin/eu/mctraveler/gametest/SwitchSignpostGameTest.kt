package eu.mctraveler.gametest

import eu.mctraveler.text.Paint
import eu.mctraveler.worlds.BankedPositions
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component

/**
 * `/switch` after the merge: the signpost, word for word (ticket 08).
 *
 * This is the whole of the migration's communication to thirteen thousand
 * players, so the wording is asserted the way every other player-facing message
 * in this repo is — as the exact [Component] a real player is sent, restated
 * here independently of the code that builds it.
 *
 * The three cases are the three a player can be in: the merge recorded where
 * their other base went, it recorded nothing for them, or there is no artifact
 * on this server at all — which is the state every unmerged server is in, and
 * the one the gametest server itself starts in.
 */
class SwitchSignpostGameTest {

    private companion object {
        /** Somewhere with a known block position, including a negative to pin the flooring. */
        const val STANDING_X = 100.5
        const val STANDING_Y = 64.0
        const val STANDING_Z = -200.5
        const val STANDING_BLOCK = "100/64/-201"

        /** A base that was in Secondary's nether, at the coordinates the merge moved it to. */
        const val BANKED_X = 1024.5
        const val BANKED_Y = 70.0
        const val BANKED_Z = -512.5
        const val BANKED_BLOCK = "1024/70/-513"
    }

    @GameTest
    fun switchExplainsTheMergeAndMovesNobody(helper: GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "T08Signpost")
        val world = player.level().dimension()
        try {
            player.setPos(STANDING_X, STANDING_Y, STANDING_Z)
            player.runCommand("switch")

            helper.assertValueEqual(
                player.messages.last(),
                signpost(otherBase = null),
                "the /switch signpost for a player with no other base",
            )
            // The command that used to Travel now moves nobody at all.
            helper.assertValueEqual(player.level().dimension(), world, "the World /switch leaves the player in")
            helper.assertValueEqual(
                listOf(player.x, player.y, player.z),
                listOf(STANDING_X, STANDING_Y, STANDING_Z),
                "the position /switch leaves the player at",
            )
        } finally {
            player.leave()
        }
        helper.succeed()
    }

    @GameTest
    fun switchTellsAPlayerWhereTheirOtherBaseWent(helper: GameTestHelper) {
        val artifact = artifact(helper)
        val player = MessageCapturingPlayer.join(helper, "T08Banked")
        val newcomer = MessageCapturingPlayer.join(helper, "T08Newcomer")
        try {
            player.setPos(STANDING_X, STANDING_Y, STANDING_Z)
            newcomer.setPos(STANDING_X, STANDING_Y, STANDING_Z)
            writeBankedPositions(artifact, player.uuid)

            player.runCommand("switch")
            helper.assertValueEqual(
                player.messages.last(),
                signpost(
                    Paint.gray(
                        "Your other base — where you last stood in ",
                        Paint.green("Secondary"),
                        " — is now at ",
                        Paint.white(BANKED_BLOCK),
                        " in ",
                        Paint.green("the Nether"),
                        ".",
                    ),
                ),
                "the /switch signpost for a player whose other base the merge recorded",
            )

            // A player who joined after the merge is named nowhere in the
            // artifact, and is told nothing about a base they never had.
            newcomer.runCommand("switch")
            helper.assertValueEqual(
                newcomer.messages.last(),
                signpost(otherBase = null),
                "the /switch signpost for a player who joined after the merge",
            )
        } finally {
            Files.deleteIfExists(artifact)
            player.leave()
            newcomer.leave()
        }
        helper.succeed()
    }

    /**
     * The signpost as a player reads it, said again here in full rather than
     * borrowed from the command: this test is the pin on the wording, so it has
     * to spell the words out itself.
     */
    private fun signpost(otherBase: Component?): Component {
        val lines = mutableListOf<Any?>(
            Paint.darkGray("--["), " ", Paint.green.bold("One World"), " ", Paint.darkGray("]--"), "\n",
            Paint.gray(
                "Primary and Secondary have merged into one map. There is nowhere left to " +
                    "switch to — Secondary is somewhere you can walk to now.",
            ),
            "\n",
            Paint.gray(
                "You are at ",
                Paint.white(STANDING_BLOCK),
                " in ",
                Paint.green("the Overworld"),
                ".",
            ),
            "\n",
        )
        if (otherBase != null) {
            lines += otherBase
            lines += "\n"
        }
        lines += Paint.gray(
            Paint.aqua("Bed"),
            " and ",
            Paint.aqua("Spawn"),
            " on your Teleportation Crystal still work exactly as they always did.",
        )
        return Paint(*lines.toTypedArray())
    }

    private fun artifact(helper: GameTestHelper): Path =
        helper.level.server.serverDirectory.resolve("mctraveler").resolve(BankedPositions.FILE_NAME)

    /** The merge's artifact, in exactly the shape the player sweep writes it. */
    private fun writeBankedPositions(file: Path, uuid: UUID) {
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """
            {
              "mergedAt": "2026-08-02T00:49:31.123456Z",
              "offset": {"x": 8192, "z": -4096},
              "players": {
                "$uuid": {"world":"secondary","dimension":"minecraft:the_nether","x":$BANKED_X,"y":$BANKED_Y,"z":$BANKED_Z}
              }
            }

            """.trimIndent(),
        )
    }
}
