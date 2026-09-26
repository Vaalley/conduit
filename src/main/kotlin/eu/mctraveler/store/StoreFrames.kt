package eu.mctraveler.store

import eu.mctraveler.economy.Economy
import eu.mctraveler.mixin.StoreFrameAccessor
import eu.mctraveler.text.Paint
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
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
     * Issue #91: the item sitting in a store's frame carries its own price as
     * its display name, so a passer-by looking at the frame sees "Buy $<price>"
     * for a store they can buy from, "Sell $<price>" for one that's buying —
     * vanilla renders a named item's floating label whenever a player looks
     * at the frame holding it, the same way it does for a named map. No lore
     * lines survive underneath it.
     */
    fun label(frame: ItemFrame, record: StoreRecord) {
        val current = frame.item
        if (current.isEmpty) return
        frame.setItem(labeled(current, record), false)
    }

    private fun labeled(stack: ItemStack, record: StoreRecord): ItemStack {
        val copy = stack.copy()
        copy.set(DataComponents.CUSTOM_NAME, tagFor(record))
        copy.set(DataComponents.LORE, ItemLore(emptyList()))
        return copy
    }

    /** The "Buy $<price>"/"Sell $<price>" tag: what a passer-by can do at this frame. */
    fun tagFor(record: StoreRecord): Component = Paint(wordFor(record), " ", Paint.white(Economy.format(record.pricePerItem)))

    /** Just the styled word — "Buy" or "Sell" — with no price, for the menu title. */
    fun wordFor(record: StoreRecord): Component = when (record.kind) {
        StoreKind.SELL -> Paint.green.bold("Buy")
        StoreKind.BUY -> Paint.red.bold("Sell")
    }
}
