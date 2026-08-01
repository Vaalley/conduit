package eu.mctraveler.region

import eu.mctraveler.text.Paint
import java.util.UUID
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.BlockEvents
import net.fabricmc.fabric.api.event.player.ItemEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState

/**
 * What a region stops a player doing: digging, building, editing signs, taking
 * from containers, using items, and harming what lives inside (spec User
 * Stories 34 and 38; inventory §2.8's protection hooks) — and, where a region
 * asks for it, working its doors, its switches, and the ground under a fall
 * (spec User Story 36's three `DISABLE_` flags).
 *
 * What a region stops the *world* doing — explosions, fire, pistons, creatures
 * — is [RegionEnvironment]. The line between them is whether anyone is asking:
 * everything here has a player to refuse and answers with the Portal's one
 * message; nothing there does, and all of it is silent.
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
    private const val DISABLE_PLAYER_FALL_DAMAGE = "DISABLE_PLAYER_FALL_DAMAGE"
    private const val DISABLE_PUBLIC_REDSTONE_TRIGGERS = "DISABLE_PUBLIC_REDSTONE_TRIGGERS"
    private const val DISABLE_GATES = "DISABLE_GATES"

    /** The region each player was standing in when they opened their container. */
    private val containerRegions = HashMap<UUID, Region>()

    /**
     * Items whose use no region refuses (see [exemptItem]). Registered once at
     * mod init, so a restart cannot lose one.
     */
    private val itemExemptions = mutableListOf<(ItemStack) -> Boolean>()

    /** Menus this mod owns itself (see [exemptMenu]). */
    private val menuExemptions = mutableListOf<(AbstractContainerMenu) -> Boolean>()

    /**
     * Exempts the items [exemption] accepts from item-use protection, wherever
     * they are used: no region refuses them and none reports a refusal.
     *
     * The Teleportation Crystal is the reason this exists (spec deviation 13).
     * Nucleus's crystal listener never consulted region protection at all, so
     * the menu opened standing anywhere; ours has to say so explicitly, because
     * region protection is registered first and would otherwise refuse the
     * right-click before the crystal ever saw it. An exemption rather than
     * listener ordering: ordering is invisible at the point it matters, and one
     * reordering of [eu.mctraveler.MCTraveler.onInitialize] would silently take
     * the crystal away from every player standing on someone else's land.
     */
    fun exemptItem(exemption: (ItemStack) -> Boolean) {
        itemExemptions.add(exemption)
    }

    /**
     * Exempts the menus [exemption] accepts from container protection and from
     * the container-region session (spec deviation 16).
     *
     * A mod-owned menu is not a chest standing in someone's region — it is a
     * screen this server drew, holding nothing anyone can take. Nucleus's menus
     * were plugin-owned inventories its region listeners never looked at.
     */
    fun exemptMenu(exemption: (AbstractContainerMenu) -> Boolean) {
        menuExemptions.add(exemption)
    }

    /** Whether [menu] belongs to the mod rather than to the world. */
    @JvmStatic
    fun isModOwnedMenu(menu: AbstractContainerMenu): Boolean =
        menuExemptions.any { it(menu) }

    private fun isExemptItem(stack: ItemStack): Boolean =
        !stack.isEmpty && itemExemptions.any { it(stack) }

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
        // DISABLE_GATES / DISABLE_PUBLIC_REDSTONE_TRIGGERS are the flags that
        // close the rest.
        ItemEvents.USE_ON.register { context ->
            val player = context.player
            // This event's "not my business" answer is null, not PASS.
            if (player is ServerPlayer &&
                !isExemptItem(context.itemInHand) &&
                !allowsBlockChange(player, context.level, context.clickedPos)
            ) {
                InteractionResult.FAIL
            } else {
                null
            }
        }

        // ---- the block's own right-click behaviour ----
        // Ticket 14 left this open on purpose (see the class docs on
        // ItemEvents.USE_ON): these two flags are what a region owner turns on
        // to close it, one for the doors and one for the switches.
        BlockEvents.USE_WITHOUT_ITEM.register { state, level, pos, player, _ ->
            // Another event whose "not my business" answer is null, not PASS.
            if (player is ServerPlayer && !allowsBlockUse(player, level, pos, state)) {
                InteractionResult.FAIL
            } else {
                null
            }
        }

        // ---- fall damage ----
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            entity !is ServerPlayer ||
                !source.`is`(DamageTypeTags.IS_FALL) ||
                allowsFallDamage(entity)
        }

        // ---- item use ----
        UseItemCallback.EVENT.register { player, _, hand ->
            allowedOrFail(player !is ServerPlayer || allowsItemUse(player, player.getItemInHand(hand)))
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

    /**
     * Whether [player] may work the block at [pos] the way it is meant to be
     * worked — open the door, press the button, pull the lever. Only two kinds
     * of block can refuse, and only when their region asks them to:
     * `DISABLE_GATES` closes the doors, gates and trapdoors,
     * `DISABLE_PUBLIC_REDSTONE_TRIGGERS` the buttons and levers. Both are
     * restrictions on non-members alone (residents, and anyone at all in a
     * `PUBLIC` region, are unaffected), and a false answer has already said so.
     */
    private fun allowsBlockUse(player: ServerPlayer, level: Level, pos: BlockPos, state: BlockState): Boolean {
        val flag = restrictingFlagFor(state) ?: return true
        val region = regionRefusing(player, level, pos, flag) ?: return true
        return refuse(player, region)
    }

    /**
     * Whether [player] may set off the pressure plate at [pos] —
     * `DISABLE_PUBLIC_REDSTONE_TRIGGERS` again, since a plate is a trigger a
     * stranger works with their feet.
     *
     * Silent, unlike its right-clicked cousins: standing is not an attempt, and
     * a plate is asked this on every tick a foot is on it.
     */
    @JvmStatic
    fun allowsPressurePlate(player: ServerPlayer, level: Level, pos: BlockPos): Boolean =
        regionRefusing(player, level, pos, DISABLE_PUBLIC_REDSTONE_TRIGGERS) == null

    /** Which flag, if any, can take this block's own behaviour away from a stranger. */
    private fun restrictingFlagFor(state: BlockState): String? = when (state.block) {
        is DoorBlock, is FenceGateBlock, is TrapDoorBlock -> DISABLE_GATES
        is ButtonBlock, is LeverBlock -> DISABLE_PUBLIC_REDSTONE_TRIGGERS
        else -> null
    }

    /**
     * The region at [pos] that refuses [player] because it flies [flag], or
     * null — no region, a member, or the flag is off.
     */
    private fun regionRefusing(player: ServerPlayer, level: Level, pos: BlockPos, flag: String): Region? {
        val region = RegionsFeature.regionAt(level, pos) ?: return null
        if (canModifyRegion(player, region)) return null
        return if (flag in region.flags) region else null
    }

    /**
     * Whether a fall may hurt [player] where they are standing —
     * `DISABLE_PLAYER_FALL_DAMAGE` catches everyone inside the region, member
     * or not, because it is the ground that is soft.
     */
    private fun allowsFallDamage(player: ServerPlayer): Boolean {
        val region = RegionTracker.regionOf(player) ?: return true
        return DISABLE_PLAYER_FALL_DAMAGE !in region.flags
    }

    /**
     * Whether [player] may use [stack] where they are standing. An empty hand
     * uses no item (the Portal only saw this event holding something), and an
     * exempt item — the Teleportation Crystal — is nobody's business but its
     * own. A false answer has already told them why.
     */
    private fun allowsItemUse(player: ServerPlayer, stack: ItemStack): Boolean {
        if (stack.isEmpty || isExemptItem(stack)) return true
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
