package eu.mctraveler.store

import eu.mctraveler.mixin.EntityInvulnerableAccessor
import eu.mctraveler.mixin.StoreFrameAccessor
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

object StoreFrames {
    const val TAG = "mctraveler_store"
    const val REACH = 5.0

    fun lookedAtFrame(player: ServerPlayer): ItemFrame? {
        val hit = ProjectileUtil.getHitResultOnViewVector(player, { it is ItemFrame }, REACH)
            as? EntityHitResult
            ?: return null
        val block = player.pick(REACH, 1.0f, false)
        if (block.type == HitResult.Type.BLOCK &&
            block.location.distanceToSqr(player.eyePosition) < hit.location.distanceToSqr(player.eyePosition)
        ) return null
        return hit.entity as? ItemFrame
    }

    fun mark(frame: ItemFrame) {
        frame.addTag(TAG)
        (frame as EntityInvulnerableAccessor).`mctraveler$setInvulnerable`(true)
        (frame as StoreFrameAccessor).`mctraveler$setFixed`(true)
    }

    fun unmark(frame: ItemFrame) {
        frame.removeTag(TAG)
        (frame as EntityInvulnerableAccessor).`mctraveler$setInvulnerable`(false)
        (frame as StoreFrameAccessor).`mctraveler$setFixed`(false)
    }

    fun isMarked(frame: ItemFrame): Boolean = frame.entityTags().contains(TAG)
}
