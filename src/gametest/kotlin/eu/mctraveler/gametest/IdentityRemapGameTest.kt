package eu.mctraveler.gametest

import com.google.common.collect.LinkedHashMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
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

    /**
     * The skin half of ticket 21. The Mojang-signed `textures` property is what
     * makes an aliased player render as themselves, and it only survives if the
     * login swap carries the authenticated profile's property map across. This
     * drives `startClientVerification` — the swap's own hook point — with a
     * property-bearing profile, exactly as the Mojang auth thread does.
     */
    @GameTest
    fun anAliasedLoginKeepsTheAuthenticatedProfilesTextures(helper: GameTestHelper) {
        val properties = LinkedHashMultimap.create<String, Property>()
        properties.put("textures", Property("textures", "a-signed-payload", "a-mojang-signature"))
        val authenticated = GameProfile(UUID.randomUUID(), "AlsoJames", PropertyMap(properties))

        val profile = verifiedProfileFor(helper, authenticated)

        helper.assertValueEqual(profile.name(), "iElmo", "login username")
        val textures = profile.properties()["textures"].single()
        helper.assertValueEqual(textures.value(), "a-signed-payload", "textures payload")
        helper.assertValueEqual(
            checkNotNull(textures.signature()) { "the textures property lost its signature" },
            "a-mojang-signature",
            "textures signature",
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
        val listener = newLoginListener(helper)
        listener.handleHello(ServerboundHelloPacket(username, UUID.randomUUID()))
        return verifiedProfileOf(listener) {
            "the hello handshake for $username did not reach client verification"
        }
    }

    /**
     * Runs a fresh login listener's `startClientVerification` on [authenticated]
     * — the call the Mojang auth thread makes with the profile it got back from
     * the session server, properties and all — and returns the profile the
     * listener will verify the client as. Private in vanilla, so invoked
     * reflectively; the offline [loginProfileFor] path cannot be used here
     * because an offline profile never carries properties.
     */
    private fun verifiedProfileFor(helper: GameTestHelper, authenticated: GameProfile): GameProfile {
        val listener = newLoginListener(helper)
        val method = ServerLoginPacketListenerImpl::class.java
            .getDeclaredMethod("startClientVerification", GameProfile::class.java)
        method.isAccessible = true
        method.invoke(listener, authenticated)
        return verifiedProfileOf(listener) {
            "client verification did not start for ${authenticated.name()}"
        }
    }

    private fun newLoginListener(helper: GameTestHelper) = ServerLoginPacketListenerImpl(
        helper.level.server,
        Connection(PacketFlow.SERVERBOUND),
        false,
    )

    /**
     * The listener's `authenticatedProfile` field, read reflectively because
     * vanilla exposes no accessor before the ServerPlayer exists (runtime names
     * are Mojang mappings under the no-remap toolchain, so the name is stable).
     */
    private fun verifiedProfileOf(
        listener: ServerLoginPacketListenerImpl,
        missing: () -> String,
    ): GameProfile {
        val field = ServerLoginPacketListenerImpl::class.java.getDeclaredField("authenticatedProfile")
        field.isAccessible = true
        return checkNotNull(field.get(listener) as GameProfile?, missing)
    }
}
