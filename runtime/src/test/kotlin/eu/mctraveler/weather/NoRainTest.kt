package eu.mctraveler.weather

import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.UUID

class NoRainTest {
    private val viewer = UUID.randomUUID()

    @AfterEach
    fun clearState() {
        NoRain.setHidden(viewer, false)
    }

    @Test
    fun `hidden viewer gets clear substitutions for every weather event`() {
        NoRain.setHidden(viewer, true)

        val start = NoRain.forViewer(
            viewer,
            ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 1f),
        ) as ClientboundGameEventPacket
        val stop = ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0f)
        val rain = NoRain.forViewer(
            viewer,
            ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.7f),
        ) as ClientboundGameEventPacket
        val thunder = NoRain.forViewer(
            viewer,
            ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.8f),
        ) as ClientboundGameEventPacket

        assertEquals(ClientboundGameEventPacket.STOP_RAINING, start.event)
        assertEquals(0f, start.param)
        assertSame(stop, NoRain.forViewer(viewer, stop))
        assertEquals(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rain.event)
        assertEquals(0f, rain.param)
        assertEquals(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunder.event)
        assertEquals(0f, thunder.param)
    }

    @Test
    fun `hidden viewer leaves non-weather events and packets unchanged`() {
        NoRain.setHidden(viewer, true)
        val gameEvent = ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, 1f)
        val time = ClientboundSetTimePacket(0L, emptyMap())

        assertSame(gameEvent, NoRain.forViewer(viewer, gameEvent))
        assertSame(time, NoRain.forViewer(viewer, time))
    }

    @Test
    fun `visible viewer leaves weather packets unchanged`() {
        val packet = ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 1f)

        assertSame(packet, NoRain.forViewer(viewer, packet))
    }
}
