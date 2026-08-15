package eu.mctraveler.identity

import com.google.common.collect.LinkedHashMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property as ProfileProperty
import com.mojang.authlib.properties.PropertyMap
import java.util.UUID
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.Property
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two aliased identities (spec User Story 42), ported from the Portal's
 * TravelPatchFeature: the remap constants, the case-sensitive lookup, and the
 * pass-through for everyone else are all mined from the parity inventory §2.9.
 */
class IdentityRemapsTest {

    @Test
    fun `DemonicNoodle logs in as travelcraft2012, name and uuid both`() {
        val remapped = IdentityRemaps.remap(GameProfile(UUID.randomUUID(), "DemonicNoodle"))
        assertEquals("travelcraft2012", remapped.name())
        assertEquals(UUID.fromString("461789c5-4501-48a0-b47d-7574c9a7b9ec"), remapped.id())
    }

    @Test
    fun `AlsoJames logs in as iElmo, name and uuid both`() {
        val remapped = IdentityRemaps.remap(GameProfile(UUID.randomUUID(), "AlsoJames"))
        assertEquals("iElmo", remapped.name())
        assertEquals(UUID.fromString("be9482bb-6bcd-4df3-9cf4-9f1fb61c5e93"), remapped.id())
    }

    @Test
    fun `the authenticated profile's properties ride across the remap`() {
        val authenticated = GameProfile(UUID.randomUUID(), "AlsoJames", signedTextures("payload"))

        val remapped = IdentityRemaps.remap(authenticated)

        val textures = remapped.properties()["textures"].single()
        assertEquals("payload", textures.value())
        assertEquals("mojang-signature", textures.signature())
    }

    @Test
    fun `both alias uuids are recognised as aliased, and nobody else is`() {
        assertTrue(IdentityRemaps.isAliased(UUID.fromString("461789c5-4501-48a0-b47d-7574c9a7b9ec")))
        assertTrue(IdentityRemaps.isAliased(UUID.fromString("be9482bb-6bcd-4df3-9cf4-9f1fb61c5e93")))
        assertFalse(IdentityRemaps.isAliased(UUID.randomUUID()))
    }

    @Test
    fun `the uuid a remapped player authenticated with is not itself aliased`() {
        // The exemption keys off the uuid the ServerPlayer holds — the alias — never
        // the Mojang account behind it, which nothing downstream of login ever sees.
        val authenticated = UUID.randomUUID()
        assertFalse(IdentityRemaps.isAliased(authenticated))
        assertTrue(IdentityRemaps.isAliased(IdentityRemaps.remap(GameProfile(authenticated, "AlsoJames")).id()))
    }

    @Test
    fun `every other profile passes through untouched`() {
        val profile = GameProfile(UUID.randomUUID(), "Notch")
        assertEquals(profile, IdentityRemaps.remap(profile))
    }

    @Test
    fun `the lookup is case-sensitive, so near-miss names pass through`() {
        for (nearMiss in listOf("demonicnoodle", "DEMONICNOODLE", "alsojames", "ALSOJAMES")) {
            val profile = GameProfile(UUID.randomUUID(), nearMiss)
            assertEquals(profile, IdentityRemaps.remap(profile))
        }
    }

    @Test
    fun `a remap emits the Portal's remap log line`() {
        val messages = captureModLog { IdentityRemaps.remap(GameProfile(UUID.randomUUID(), "AlsoJames")) }
        assertEquals(listOf("Remapping AlsoJames -> iElmo"), messages)
    }

    @Test
    fun `a pass-through logs nothing`() {
        val messages = captureModLog { IdentityRemaps.remap(GameProfile(UUID.randomUUID(), "Notch")) }
        assertEquals(emptyList<String>(), messages)
    }

    /** A property map holding one Mojang-signed `textures` property, as an authenticated profile carries. */
    private fun signedTextures(value: String): PropertyMap {
        val properties = LinkedHashMultimap.create<String, ProfileProperty>()
        properties.put("textures", ProfileProperty("textures", value, "mojang-signature"))
        return PropertyMap(properties)
    }

    /** Runs [block] with a capturing appender on the mod's logger; returns the formatted messages. */
    private fun captureModLog(block: () -> Unit): List<String> {
        val messages = mutableListOf<String>()
        val appender = object : AbstractAppender("capture-remaps", null, null, true, Property.EMPTY_ARRAY) {
            override fun append(event: LogEvent) {
                messages += event.message.formattedMessage
            }
        }
        appender.start()
        val logger = LogManager.getLogger("mctraveler") as Logger
        val previousLevel = logger.level
        Configurator.setLevel("mctraveler", Level.INFO)
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.removeAppender(appender)
            Configurator.setLevel("mctraveler", previousLevel)
            appender.stop()
        }
        return messages
    }
}
