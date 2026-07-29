package eu.mctraveler.region

import eu.mctraveler.text.Paint
import java.util.UUID
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.ItemEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.level.Level

/**
 * What a region stops a player doing: digging, building, editing signs, taking
 * from containers, using items, and harming what lives inside (spec User
 * Stories 34 and 38; inventory §2.8's protection hooks).
 *
 * Enforcement is server-side cancellation of the action itself. The Portal
 * could only drop the client's packets and dress the player in a fake
 * Adventure gamemode to make the client refuse first; on a real server the
 * action simply does not happen, so the illusion — and the dig-acknowledge
 * packet that stopped it ghosting blocks — are gone (the events used here
 * resync the client themselves).
 *
 * Every refusal answers with the Portal's one message, and every decision is
 * taken from live state — the region under the block or under the player's
 * feet, and that region's current member set. Nothing about a player's
 * protection is cached, so a teleport into a region, a `/rg add`, or an
 * `/rg flag PUBLIC` is in force for the player's very next action. The one
 * deliberate exception is the container session (see [containerOpened]).
 */
object RegionProtection {

    private const val PUBLIC = "PUBLIC"
    private const val ENABLE_PUBLIC_CONTAINERS = "ENABLE_PUBLIC_CONTAINERS"
    private const val ENABLE_PUBLIC_VILLAGER_TRADING = "ENABLE_PUBLIC_VILLAGER_TRADING"
    private const val DISABLE_ANIMAL_PROTECTION = "DISABLE_ANIMAL_PROTECTION"

    /** The region each player was standing in when they opened their container. */
    private val containerRegions = HashMap<UUID, Region>()

    /**
     * Whether [player] may change what is inside [region] — a resident, or
     * anyone at all when the region is `PUBLIC`. A null region is unprotected
     * ground.
     *
     * Admins are deliberately absent: operator status bypasses region
     * *management* (see [RegionsFeature.isAdmin]) and never protection itself.
     */
    fun canModifyRegion(player: ServerPlayer, region: Region?): Boolean =
        region == null || region.isResident(player.uuid) || PUBLIC in region.flags

    fun register() {
        // ---- digging ----
        // Refused as the dig starts, which is also the whole of it for an
        // instant break; Fabric puts the block back on the client for us.
        AttackBlockCallback.EVENT.register { player, level, _, pos, _ ->
            allowedOrFail(player !is ServerPlayer || allowsBlockChange(player, level, pos))
        }
        // The authoritative refusal: whatever route reached "this block is
        // about to break", it does not break.
        PlayerBlockBreakEvents.BEFORE.register { level, player, pos, _, _ ->
            player !is ServerPlayer || allowsBlockChange(player, level, pos)
        }

        // ---- building ----
        // An item applied to a block: placing, tilling, striking a light. The
        // block's own right-click behaviour (opening a chest, a door, a
        // button) is a separate step in vanilla and is deliberately left
        // alone — the container rule below governs what may then be taken, and
        // ticket 15's DISABLE_GATES / DISABLE_PUBLIC_REDSTONE_TRIGGERS are the
        // flags that close the rest.
        ItemEvents.USE_ON.register { context ->
            val player = context.player
            // This event's "not my business" answer is null, not PASS.
            if (player is ServerPlayer && !allowsBlockChange(player, context.level, context.clickedPos)) {
                InteractionResult.FAIL
            } else {
                null
            }
        }

        // ---- item use ----
        // The Portal only saw this when the player actually held something;
        // an empty hand still does not use an item, so the guard stays.
        UseItemCallback.EVENT.register { player, _, hand ->
            allowedOrFail(
                player !is ServerPlayer ||
                    player.getItemInHand(hand).isEmpty ||
                    allowsItemUse(player),
            )
        }

        // ---- entities ----
        AttackEntityCallback.EVENT.register { player, _, _, _, _ ->
            allowedOrFail(player !is ServerPlayer || allowsEntityAttack(player))
        }
        UseEntityCallback.EVENT.register { player, _, hand, _, _ ->
            allowedOrFail(player !is ServerPlayer || allowsEntityInteract(player, hand))
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            containerRegions.remove(handler.player.uuid)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { containerRegions.clear() }
    }

    /**
     * Whether [player] may change the block at [pos] — digging, building, and
     * editing a sign all ask this of the *target block's* region, not the one
     * the player is standing in. A false answer has already told them why.
     */
    @JvmStatic
    fun allowsBlockChange(player: ServerPlayer, level: Level, pos: BlockPos): Boolean {
        val region = RegionsFeature.regionAt(level, pos) ?: return true
        return canModifyRegion(player, region) || refuse(player, region)
    }

    /**
     * Remembers the region [player] was standing in as they opened a
     * container. The Portal captured it at open time and let it govern the
     * whole session, so stepping outside (or a region appearing) mid-session
     * cannot change what the open chest allows.
     */
    @JvmStatic
    fun containerOpened(player: ServerPlayer) {
        val region = RegionTracker.regionOf(player)
        if (region == null) {
            containerRegions.remove(player.uuid)
        } else {
            containerRegions[player.uuid] = region
        }
    }

    /** Releases the captured region: the container session is over. */
    @JvmStatic
    fun containerClosed(player: ServerPlayer) {
        containerRegions.remove(player.uuid)
    }

    /**
     * Whether [player] may click in the container they have open. Residents
     * may; so may anyone when the region is `PUBLIC` or opens its containers
     * to the public. A false answer has already told them why.
     */
    @JvmStatic
    fun allowsContainerUse(player: ServerPlayer): Boolean {
        val region = containerRegions[player.uuid] ?: return true
        if (canModifyRegion(player, region) || ENABLE_PUBLIC_CONTAINERS in region.flags) return true
        return refuse(player, region)
    }

    private fun allowsItemUse(player: ServerPlayer): Boolean {
        val region = RegionTracker.regionOf(player) ?: return true
        return canModifyRegion(player, region) || refuse(player, region)
    }

    /** Hitting anything inside a protected region is always refused. */
    private fun allowsEntityAttack(player: ServerPlayer): Boolean {
        val region = entityProtectionAround(player) ?: return true
        return refuse(player, region)
    }

    /**
     * Right-clicking an entity is refused only with something in hand: an
     * empty hand is how a villager is traded with, and the Portal let that
     * through. `ENABLE_PUBLIC_VILLAGER_TRADING` opens the held-item case too.
     */
    private fun allowsEntityInteract(player: ServerPlayer, hand: InteractionHand): Boolean {
        val region = entityProtectionAround(player) ?: return true
        if (player.getItemInHand(hand).isEmpty) return true
        if (ENABLE_PUBLIC_VILLAGER_TRADING in region.flags) return true
        return refuse(player, region)
    }

    /**
     * The region whose creatures [player] must leave alone where they stand, or
     * null when they may do as they like — outside a region, inside one they
     * can modify, or in one flying `DISABLE_ANIMAL_PROTECTION`. The Portal
     * called this animal protection; the rule it wrote covers every entity.
     */
    private fun entityProtectionAround(player: ServerPlayer): Region? {
        val region = RegionTracker.regionOf(player) ?: return null
        if (canModifyRegion(player, region)) return null
        if (DISABLE_ANIMAL_PROTECTION in region.flags) return null
        return region
    }

    /** Sends the Portal's one refusal message, and answers "not allowed". */
    private fun refuse(player: ServerPlayer, region: Region): Boolean {
        player.sendSystemMessage(Paint.error("This area is protected by ", Paint.red(region.title)))
        return false
    }

    /**
     * The interaction verdict Fabric's PASS-to-continue events expect:
     * anything but PASS cancels the action. ([ItemEvents] is the odd one out —
     * it continues on null — so it builds its verdict inline.)
     */
    private fun allowedOrFail(allowed: Boolean): InteractionResult =
        if (allowed) InteractionResult.PASS else InteractionResult.FAIL
}
