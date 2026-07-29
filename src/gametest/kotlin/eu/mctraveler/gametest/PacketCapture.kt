package eu.mctraveler.gametest

import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.protocol.BundlePacket
import net.minecraft.network.protocol.Packet
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerCommonPacketListenerImpl

/**
 * Reads the packets the server has sent to a gametest mock player.
 *
 * Mock players ([net.minecraft.gametest.framework.GameTestHelper.makeMockServerPlayerInLevel])
 * join through the real `PlayerList.placeNewPlayer` pipeline with their [Connection] wrapped
 * in an [EmbeddedChannel], whose outbound queue accumulates every clientbound packet exactly
 * as a real client would receive it — the closest headless stand-in for "what the player sees".
 */
object PacketCapture {
    private val connectionField = ServerCommonPacketListenerImpl::class.java
        .getDeclaredField("connection").apply { isAccessible = true }
    private val channelField = Connection::class.java
        .getDeclaredField("channel").apply { isAccessible = true }

    /**
     * Drains and returns every packet sent to [player] since the last drain, with bundles
     * unwrapped. Non-packet outbound messages (channel reconfiguration tasks) are skipped.
     *
     * Packets written during the current tick are still sitting in the channel's write
     * buffer — the server flushes once per tick — so the channel is flushed first, making
     * a drain immediately after an action see what that action sent.
     */
    fun drain(player: ServerPlayer): List<Packet<*>> {
        val connection = connectionField.get(player.connection) as Connection
        val channel = channelField.get(connection) as EmbeddedChannel
        channel.flush()
        return buildList {
            while (true) {
                when (val message = channel.readOutbound<Any>() ?: break) {
                    is BundlePacket<*> -> addAll(message.subPackets())
                    is Packet<*> -> add(message)
                }
            }
        }
    }

    /** Drains, keeping only packets of type [T] (in send order). */
    inline fun <reified T : Packet<*>> drainOf(player: ServerPlayer): List<T> =
        drain(player).filterIsInstance<T>()
}
