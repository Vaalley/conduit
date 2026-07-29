package eu.mctraveler.gametest

import com.mojang.authlib.GameProfile
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.UUIDUtil
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.login.ServerboundHelloPacket
import net.minecraft.server.network.ServerLoginPacketListenerImpl

/**
 * The two aliased identities at the real login seam (spec User Story 42): the
 * vanilla login listener's `startClientVerification` is the single funnel every
 * login path (Mojang auth thread, offline, singleplayer) passes through, and the
 * profile it stores is the identity all downstream state keys to — the
 * ServerPlayer, its playerdata file, the tab list, and the mod's name cache.
 *
 * Each test drives a fresh listener through `handleHello` on the live server
 * (which is offline, so the hello resolves synchronously) and asserts the
 * identity the listener ends up verifying. Fake gametest players are placed
 * directly into the world and never pass through login, so this listener-level
 * drive is the closest headless approximation of a real connection.
 */
class IdentityRemapGameTest {

    @GameTest
    fun demonicNoodleLogsInAsTravelcraft2012(helper: GameTestHelper) {
        val profile = loginProfileFor(helper, "DemonicNoodle")
        helper.assertValueEqual(profile.name(), "travelcraft2012", "login username")
        helper.assertValueEqual(
            profile.id(),
            UUID.fromString("461789c5-4501-48a0-b47d-7574c9a7b9ec"),
            "login uuid",
        )
        helper.succeed()
    }

    @GameTest
    fun alsoJamesLogsInAsIElmo(helper: GameTestHelper) {
        val profile = loginProfileFor(helper, "AlsoJames")
        helper.assertValueEqual(profile.name(), "iElmo", "login username")
        helper.assertValueEqual(
            profile.id(),
            UUID.fromString("be9482bb-6bcd-4df3-9cf4-9f1fb61c5e93"),
            "login uuid",
        )
        helper.succeed()
    }

    @GameTest
    fun unaffectedNamesLogInUntouched(helper: GameTestHelper) {
        val profile = loginProfileFor(helper, "Notch")
        helper.assertValueEqual(
            profile,
            UUIDUtil.createOfflineProfile("Notch"),
            "login profile of an unremapped name",
        )
        helper.succeed()
    }

    /**
     * Runs a fresh vanilla login listener through the hello handshake for
     * [username] and returns the profile it will verify the client as — the
     * `authenticatedProfile` field, read reflectively because vanilla exposes
     * no accessor before the ServerPlayer exists (runtime names are Mojang
     * mappings under the no-remap toolchain, so the name is stable).
     */
    private fun loginProfileFor(helper: GameTestHelper, username: String): GameProfile {
        val listener = ServerLoginPacketListenerImpl(
            helper.level.server,
            Connection(PacketFlow.SERVERBOUND),
            false,
        )
        listener.handleHello(ServerboundHelloPacket(username, UUID.randomUUID()))
        val field = ServerLoginPacketListenerImpl::class.java.getDeclaredField("authenticatedProfile")
        field.isAccessible = true
        return checkNotNull(field.get(listener) as GameProfile?) {
            "the hello handshake for $username did not reach client verification"
        }
    }
}
