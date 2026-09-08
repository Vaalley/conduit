package eu.mctraveler.moderation

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

enum class PunishmentType { BAN, MUTE, KICK, WARN, NOTE }

data class Punishment(
    val id: Int,
    val type: PunishmentType,
    val target: UUID,
    val targetName: String,
    val staff: UUID?,
    val staffName: String,
    val reason: String,
    val createdAt: Long,
    val expiresAt: Long?,
    var active: Boolean,
    var liftedAt: Long? = null,
    var liftedBy: String? = null,
)

class PunishmentStore(private val file: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val punishments = load()
    private var nextId = punishments.maxOfOrNull { it.id }?.plus(1) ?: 1

    fun add(
        type: PunishmentType,
        target: UUID,
        targetName: String,
        staff: UUID?,
        staffName: String,
        reason: String,
        expiresAt: Long?,
        active: Boolean = true,
    ): Punishment {
        val punishment = Punishment(
            id = nextId++,
            type = type,
            target = target,
            targetName = targetName,
            staff = staff,
            staffName = staffName,
            reason = reason,
            createdAt = System.currentTimeMillis(),
            expiresAt = expiresAt,
            active = active,
        )
        punishments += punishment
        save()
        return punishment
    }

    fun activeBan(target: UUID): Punishment? {
        expire()
        return punishments.lastOrNull { it.target == target && it.type == PunishmentType.BAN && it.active }
    }

    fun activeMute(target: UUID): Punishment? {
        expire()
        return punishments.lastOrNull { it.target == target && it.type == PunishmentType.MUTE && it.active }
    }

    fun lift(type: PunishmentType, target: UUID, by: String): Punishment? {
        val now = System.currentTimeMillis()
        expire()
        val lifted = punishments.filter { it.type == type && it.target == target && it.active }
        if (lifted.isEmpty()) return null
        lifted.forEach {
            it.active = false
            it.liftedAt = now
            it.liftedBy = by
        }
        save()
        return lifted.last()
    }

    fun byId(id: Int): Punishment? = punishments.firstOrNull { it.id == id }

    fun history(target: UUID): List<Punishment> =
        punishments.filter { it.target == target }.sortedByDescending { it.createdAt }

    fun activeBans(): List<Punishment> {
        expire()
        return punishments.filter { it.type == PunishmentType.BAN && it.active }
    }

    fun deactivate(id: Int, by: String): Punishment? {
        val punishment = byId(id) ?: return null
        if (!punishment.active) return null
        punishment.active = false
        punishment.liftedAt = System.currentTimeMillis()
        punishment.liftedBy = by
        save()
        return punishment
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        var changed = false
        punishments.filter { it.active && it.expiresAt != null && it.expiresAt <= now }.forEach {
            it.active = false
            changed = true
        }
        if (changed) save()
    }

    private fun load(): MutableList<Punishment> {
        if (Files.notExists(file)) return mutableListOf()
        return try {
            gson.fromJson<List<Punishment>>(Files.readString(file), object : TypeToken<List<Punishment>>() {}.type)
                ?.toMutableList() ?: mutableListOf()
        } catch (failure: Exception) {
            throw IllegalStateException("Could not read $file", failure)
        }
    }

    private fun save() {
        file.parent?.let(Files::createDirectories)
        Files.writeString(file, gson.toJson(punishments))
    }
}
