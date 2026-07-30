package eu.mctraveler.chat

import java.util.concurrent.ConcurrentLinkedDeque
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.PlayerChatMessage

/**
 * Captures in-game chat lines so the Observer Discord bot can poll them and
 * mirror them into a linked Discord channel. This is one half of the two-way
 * chat bridge; the other half is Observer posting Discord messages to Conduit's
 * existing /broadcast endpoint.
 */
data class ChatMessage(
	val timestamp: Long,
	val sender: String,
	val content: String,
)

object ChatBridge {
	private const val MAX_MESSAGES = 256

	private val messages = ConcurrentLinkedDeque<ChatMessage>()

	fun register() {
		ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ ->
			val playerMessage = message as? PlayerChatMessage ?: return@register
			val content = sanitize(playerMessage.decoratedContent())
			if (content.isNotEmpty()) {
				record(sender.gameProfile.name, content)
			}
		}
	}

	@JvmStatic
	fun record(sender: String, content: String) {
		val timestamp = System.currentTimeMillis()
		val last = messages.peekLast()
		// Drop exact back-to-back duplicates produced by rebroadcasts.
		if (last != null && last.timestamp == timestamp && last.sender == sender && last.content == content) {
			return
		}
		messages.addLast(ChatMessage(timestamp, sender, content))
		while (messages.size > MAX_MESSAGES) {
			messages.pollFirst()
		}
	}

	fun poll(since: Long): List<ChatMessage> =
		messages.filter { it.timestamp > since }.takeLast(100)
}

private fun sanitize(content: Component): String {
	return content.string
		.replace(Regex("[\r\n]"), " ")
		.replace("§", "")
		.trim()
}
