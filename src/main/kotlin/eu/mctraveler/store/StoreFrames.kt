package eu.mctraveler.store

import eu.mctraveler.economy.Economy
import eu.mctraveler.mixin.StoreFrameAccessor
import eu.mctraveler.text.Paint
import net.minecraft.network.chat.Component
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
        frame.setPermanentlyInvulnerable(true)
        (frame as StoreFrameAccessor).`mctraveler$setFixed`(true)
    }

    fun unmark(frame: ItemFrame) {
        frame.removeTag(TAG)
        frame.setPermanentlyInvulnerable(false)
        (frame as StoreFrameAccessor).`mctraveler$setFixed`(false)
    }

    fun isMarked(frame: ItemFrame): Boolean = frame.entityTags().contains(TAG)

    /**
     * Issue #91: a store's frame carries its price as a name tag, so a
     * passer-by sees what it costs before they ever open it — "Buy $<price>"
     * for a store you can buy from, "Sell $<price>" for one that's buying.
     */
    fun label(frame: ItemFrame, record: StoreRecord) {
        frame.customName = labelFor(record)
        frame.isCustomNameVisible = true
    }

    fun clearLabel(frame: ItemFrame) {
        frame.customName = null
        frame.isCustomNameVisible = false
    }

    private fun labelFor(record: StoreRecord): Component = when (record.kind) {
        StoreKind.SELL -> Paint(Paint.red.bold("Buy"), " ", Paint.gray(Economy.format(record.pricePerItem)))
        StoreKind.BUY -> Paint(Paint.green.bold("Sell"), " ", Paint.gray(Economy.format(record.pricePerItem)))
    }
}
