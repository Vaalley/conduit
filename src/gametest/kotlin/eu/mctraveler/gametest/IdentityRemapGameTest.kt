package eu.mctraveler.gametest

import com.google.common.collect.LinkedHashMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.ChatFormatting
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.login.ServerboundHelloPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.network.ServerLoginPacketListenerImpl

/**
 * The two aliased identities at the real login seam (spec User Story 42): the
 * vanilla login listener's `startClientVerification` is the single funnel every
 * login path (Mojang auth thread, offline, singleplayer) passes through, and the
 * profile it stores is the identity all downstream state keys to — the
 * ServerPlayer, its playerdata file, the tab list, and the mod's name cache.
 *
 * The login tests drive a fresh listener through `handleHello` on the live
 * server (which is offline, so the hello resolves synchronously) and assert the
 * identity the listener ends up verifying. Fake gametest players are placed
 * directly into the world and never pass through login, so this listener-level
 * drive is the closest headless approximation of a real connection.
 *
 * The chat test comes at it from the other end: a test player joined *with* an
 * alias uuid exercises the real chat listener, which is where ticket 21's
 * secure-chat exemption lives.
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

    /**
     * The chat half of ticket 21, at the seam that decides whether anyone else
     * sees the line. An aliased player can never sign a message — vanilla's
     * client refuses to open a chat session for a uuid it did not authenticate
     * with — and a client whose server enforces secure chat drops unsigned
     * *player* chat unseen. So their line goes out as disguised chat, which no
     * client validates, while everyone else keeps the signed player-chat path.
     */
    @GameTest
    fun anAliasedPlayersChatReachesEveryoneAsDisguisedChat(helper: GameTestHelper) {
        val server = helper.level.server
        val observer = TestPlayer.join(server, "RemapObserver")
        val aliased = TestPlayer.joinAs(server, GameProfile(ALIAS_UUID, "iElmo"))
        val ordinary = TestPlayer.join(server, "RemapOrdinary")

        helper.runAfterDelay(2) {
            aliased.chat("aliased hello")
            ordinary.chat("ordinary hello")
        }
        helper.succeedWhen {
            val disguised = observer.disguisedChatPackets()
                .firstOrNull { it.message().string == "aliased hello" }
                ?: throw helper.assertionException("the aliased player's line did not reach the observer")
            val bound = disguised.chatType()
            if (bound.chatType().unwrapKey().orElse(null) != MCT_CHAT_TYPE) {
                throw helper.assertionException("the aliased line was not bound to the mctraveler:chat type")
            }
            if (bound.name() != Component.literal("iElmo").withStyle(ChatFormatting.GREEN)) {
                throw helper.assertionException("the aliased line's sender is not the plain green username")
            }
            if (observer.chatPackets().any { it.body().content() == "aliased hello" }) {
                throw helper.assertionException(
                    "the aliased line also went out as player chat, which an enforcing client drops unseen",
                )
            }
            // The control: nothing changes for anyone else.
            if (observer.chatPackets().none { it.body().content() == "ordinary hello" }) {
                throw helper.assertionException("an ordinary player's chat left the signed player-chat path")
            }
            if (observer.disguisedChatPackets().any { it.message().string == "ordinary hello" }) {
                throw helper.assertionException("an ordinary player's chat was disguised")
            }
        }
    }

    /**
     * The receive half of the aliased players' chat (ticket 22, deviation 58).
     * A vanilla client gates player-voiced chat — and only player-voiced chat —
     * behind client-account state the server cannot see (friends-only, chat
     * restrictions, commands-only visibility), and the two aliased players
     * proved to be on the wrong side of one of those gates in production: they
     * could send, and saw system traffic, but no one else's chat. The system
     * channel passes every such gate, so it is the channel they now get chat on.
     */
    @GameTest
    fun anAliasedPlayerReceivesChatAsSystemMessages(helper: GameTestHelper) {
        val server = helper.level.server
        val aliased = TestPlayer.joinAs(server, GameProfile(ALIAS_UUID, "iElmo"))
        val ordinary = TestPlayer.join(server, "RemapTalker")

        helper.runAfterDelay(2) {
            ordinary.chat("hello iElmo")
            aliased.chat("hello back")
        }
        helper.succeedWhen {
            if (aliased.systemMessages().none { it.string == "RemapTalker hello iElmo" }) {
                throw helper.assertionException(
                    "an ordinary player's line did not reach the aliased player as a decorated system line",
                )
            }
            // Their own echo arrives on the same channel, so they see themselves speak.
            if (aliased.systemMessages().none { it.string == "iElmo hello back" }) {
                throw helper.assertionException("the aliased player's own line did not echo back as a system line")
            }
            // Nothing player-voiced goes to an aliased client — either kind may be dropped unseen there.
            if (aliased.chatPackets().isNotEmpty() || aliased.disguisedChatPackets().isNotEmpty()) {
                throw helper.assertionException(
                    "the aliased player was sent player or disguised chat, which their client may hide",
                )
            }
            // The control: an ordinary player keeps the signed player-chat path.
            if (ordinary.chatPackets().none { it.body().content() == "hello iElmo" }) {
                throw helper.assertionException("an ordinary player's chat left the player-chat path")
            }
        }
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

    private companion object {
        /** AlsoJames's alias, stated from the spec rather than read off the implementation. */
        val ALIAS_UUID: UUID = UUID.fromString("be9482bb-6bcd-4df3-9cf4-9f1fb61c5e93")

        /** The chat type the mod ships in its datapack, stated independently of the implementation. */
        val MCT_CHAT_TYPE: ResourceKey<ChatType> =
            ResourceKey.create(Registries.CHAT_TYPE, Identifier.fromNamespaceAndPath("mctraveler", "chat"))
    }
}
