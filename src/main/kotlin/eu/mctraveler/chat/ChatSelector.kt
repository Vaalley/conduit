package eu.mctraveler.chat

import java.util.UUID

object ChatSelector {
    sealed interface Mode {
        data object DEFAULT : Mode
        data object SERVER : Mode
        data class PLAYERS(val recipients: Set<UUID>) : Mode
        data object REGION : Mode
    }

    private val modes = mutableMapOf<UUID, Mode>()

    fun modeOf(uuid: UUID): Mode = modes[uuid] ?: Mode.DEFAULT
    fun set(uuid: UUID, mode: Mode) { modes[uuid] = mode }
    fun clear(uuid: UUID) { modes.remove(uuid) }
    fun parsePlayers(raw: String): List<String> =
        raw.split(',').map(String::trim).filter(String::isNotEmpty)
}
