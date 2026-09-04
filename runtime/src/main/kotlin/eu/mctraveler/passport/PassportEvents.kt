package eu.mctraveler.passport

import java.util.concurrent.ConcurrentLinkedDeque

data class PostcardEvent(
    val type: String = "postcard",
    val at: Long,
    val player: String,
    val dimension: String,
    val biome: String,
    val region: RegionRef?,
    val x: Int,
    val y: Int,
    val z: Int,
    val dayTime: Long,
    val raining: Boolean,
    val thundering: Boolean,
    val caption: String?,
)

data class RegionRef(
    val id: String,
    val title: String,
    val embassy: Boolean,
    val owner: String?,
)

data class StampEvent(
    val type: String = "stamp",
    val at: Long,
    val player: String,
    val stamp: StampRef,
)

data class StampRef(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
)

object PassportEvents {
    private const val MAX_EVENTS = 500
    private const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

    private val events = ConcurrentLinkedDeque<Any>()

    fun record(event: Any) {
        val now = System.currentTimeMillis()
        while (events.peekFirst()?.let { eventAt(it) < now - MAX_AGE_MILLIS } == true) {
            events.pollFirst()
        }
        events.addLast(event)
        while (events.size > MAX_EVENTS) events.pollFirst()
    }

    fun poll(since: Long): List<Any> =
        events.filter { eventAt(it) > since }.takeLast(100)

    fun clear() {
        events.clear()
    }

    private fun eventAt(event: Any): Long = when (event) {
        is PostcardEvent -> event.at
        is StampEvent -> event.at
        else -> System.currentTimeMillis()
    }
}
