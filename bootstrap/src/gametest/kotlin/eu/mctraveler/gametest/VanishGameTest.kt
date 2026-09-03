package eu.mctraveler.gametest

import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.ChatFormatting
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component

/**
 * `/vanish` (issue #47): an admin disappears for everyone without operator status —
 * a fake leave line, out of the server-list count and sample — while other admins
 * keep seeing them and get a plain status line instead.
 */
class VanishGameTest {

    @GameTest
    fun vanishFakesALeaveForNonAdminsAndStatusesTheAdmins(helper: GameTestHelper) {
        val server = helper.level.server
        val stranger = TestPlayer.join(server, "VanStranger")
        val adminObserver = TestPlayer.join(server, "VanAdminObs").also { it.op() }
        val admin = TestPlayer.join(server, "VanAdmin").also { it.op() }

        admin.runCommand("vanish")

        helper.succeedWhen {
            check(admin.player.isSpectator) { "the vanished admin is not in spectator mode" }
            check(stranger.systemMessages().any { it == leaveLine("VanAdmin") }) {
                "the non-admin did not see a fake leave line for the vanishing admin"
            }
            check(stranger.systemMessages().none { it == vanishStatusLine("VanAdmin", "went into vanish mode") }) {
                "the non-admin was shown the admin-only vanish status line"
            }
            check(adminObserver.systemMessages().any { it == vanishStatusLine("VanAdmin", "went into vanish mode") }) {
                "the other admin did not see the vanish status line"
            }
            check(adminObserver.systemMessages().none { it == leaveLine("VanAdmin") }) {
                "the other admin was fooled by the fake leave line"
            }
            cleanUp(admin, adminObserver, stranger)
        }
    }

    @GameTest
    fun unvanishFakesAJoinForNonAdmins(helper: GameTestHelper) {
        val server = helper.level.server
        val stranger = TestPlayer.join(server, "BackStranger")
        val admin = TestPlayer.join(server, "BackAdmin").also { it.op() }

        admin.runCommand("vanish")
        admin.runCommand("vanish")

        helper.succeedWhen {
            check(!admin.player.isSpectator) { "the admin was not returned to their prior game mode" }
            check(stranger.systemMessages().any { it == joinLine("BackAdmin") }) {
                "the non-admin did not see a fake join line when the admin returned"
            }
            cleanUp(admin, stranger)
        }
    }

    @GameTest
    fun vanishedAdminIsNotCountedOrSampledOnTheServerList(helper: GameTestHelper) {
        val server = helper.level.server
        val admin = TestPlayer.join(server, "SampleAdmin").also { it.op() }

        admin.runCommand("vanish")

        helper.succeedWhen {
            // The live status can lag the roster on this shared server, so compare the
            // decorated payload against the raw one it was built from rather than the
            // live player count.
            val status = checkNotNull(server.status) { "the server has not built a status response" }
            val raw = status.players().orElseThrow().online()
            val decorated = eu.mctraveler.motd.Motd.decorate(status, server).players().orElseThrow()
            helper.assertValueEqual(decorated.online(), raw - 1, "advertised count drops the vanished admin")

            // And with the admin visible again the decoration is a no-op on the count.
            admin.runCommand("vanish")
            val restored = eu.mctraveler.motd.Motd.decorate(status, server).players().orElseThrow()
            helper.assertValueEqual(restored.online(), raw, "advertised count is untouched with nobody vanished")
            cleanUp(admin)
        }
    }

    private fun cleanUp(vararg players: TestPlayer) {
        for (player in players) {
            if (player.player.isSpectator) player.runCommand("vanish")
            player.player.level().server.playerList.deop(player.player.nameAndId())
            player.disconnect()
        }
    }

    private fun TestPlayer.op() {
        player.level().server.playerList.op(player.nameAndId())
    }

    /** `[-] <name> left.` — the shared leave line. */
    private fun leaveLine(name: String): Component =
        Component.empty().withStyle(ChatFormatting.GRAY)
            .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("-").withStyle(ChatFormatting.RED))
            .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(" "))
            .append(Component.literal(name).withStyle(ChatFormatting.RED))
            .append(Component.literal(" left."))

    /** `[+] <name> joined` — no country from a loopback test connection. */
    private fun joinLine(name: String): Component =
        Component.empty().withStyle(ChatFormatting.GRAY)
            .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("+").withStyle(ChatFormatting.GREEN))
            .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(" "))
            .append(Component.literal(name).withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" joined"))

    /** `[V] <name> <what>` — the admin-only vanish status line. */
    private fun vanishStatusLine(name: String, what: String): Component =
        Component.empty().withStyle(ChatFormatting.GRAY)
            .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("V").withStyle(ChatFormatting.AQUA))
            .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(" "))
            .append(Component.literal(name).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" "))
            .append(Component.literal(what))
}
