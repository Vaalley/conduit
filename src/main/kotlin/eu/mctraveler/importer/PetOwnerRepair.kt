package eu.mctraveler.importer

import eu.mctraveler.MCTraveler
import eu.mctraveler.mixin.AbstractHorseOwnerAccessor
import eu.mctraveler.persistence.NameCache
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityReference
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.animal.equine.AbstractHorse
import java.util.UUID

/**
 * Repairs pet owners written by the old offline-mode server when the entity
 * first loads into the online-mode server.
 */
object PetOwnerRepair {
    private var cachedSize = -1
    private var cachedOwners: Map<UUID, UUID> = emptyMap()

    /** Register the entity-load repair hook. */
    fun register() {
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ -> repair(entity) }
    }

    /**
     * Finds the real UUID whose cached username produces [offline] under the
     * old server's offline-mode UUID scheme.
     */
    fun realOwnerFor(offline: UUID, names: Map<UUID, String>): UUID? =
        names.entries.firstOrNull { (real, username) ->
            real != offline && OfflineUuid.of(username) == offline
        }?.key

    /** Replaces an imported offline owner reference, returning whether it did. */
    fun repair(entity: Entity): Boolean {
        val reference = when (entity) {
            is TamableAnimal -> entity.ownerReference
            is AbstractHorse -> entity.ownerReference
            else -> return false
        } ?: return false
        val names = MCTraveler.persistence?.names ?: return false
        val owners = ownersFor(names)
        val offline = reference.uuid
        val real = owners[offline] ?: return false

        when (entity) {
            is TamableAnimal -> entity.setOwnerReference(EntityReference.of<LivingEntity>(real))
            is AbstractHorse ->
                (entity as AbstractHorseOwnerAccessor).`mctraveler$setOwner`(EntityReference.of(real))
        }
        MCTraveler.LOGGER.info(
            "Re-owned {} {} from offline {} to {}",
            entity.type,
            entity.uuid,
            offline,
            real,
        )
        return true
    }

    private fun ownersFor(names: NameCache): Map<UUID, UUID> {
        if (names.size != cachedSize) {
            cachedOwners = names.entries().entries.mapNotNull { (uuid, name) ->
                OfflineUuid.of(name).takeIf { it != uuid }?.let { it to uuid }
            }.toMap()
            cachedSize = names.size
        }
        return cachedOwners
    }
}
