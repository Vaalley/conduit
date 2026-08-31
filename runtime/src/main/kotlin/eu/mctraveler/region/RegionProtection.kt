package eu.mctraveler.region

import eu.mctraveler.reloadable
import eu.mctraveler.text.Paint
import java.util.IdentityHashMap
import java.util.UUID
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.ItemEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.tags.BlockTags
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.PlayerEnderChestContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.EndPortalFrameBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.WeightedPressurePlateBlock
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
 * everything here has a player to refuse and answers false; its refusal message
 * may be throttled. Nothing there does, and all of it is silent.
 *
 * Enforcement is server-side cancellation of the action itself. In addition,
 * [RegionTracker] keeps survival players in Adventure mode while they stand in
 * a region they cannot modify, so the client suppresses impossible block
 * changes before they reach this authoritative layer.
 *
 * A refusal always returns false; when its message is not throttled, it answers
 * with the Portal's one message. Every decision is taken from live state — the
 * region under the block or under the player's feet, and that region's current
 * member set. Nothing about a player's protection is cached, so a teleport into
 * a region, a `/rg add`, or a `/rg flag PUBLIC` is in force for the player's very
 * next action. The one deliberate exception is the container session (see
 * [containerOpened]).
 */
object RegionProtection {

    private const val PUBLIC = "PUBLIC"
    private const val ENABLE_PUBLIC_CONTAINERS = "ENABLE_PUBLIC_CONTAINERS"
    private const val ENABLE_PUBLIC_VILLAGER_TRADING = "ENABLE_PUBLIC_VILLAGER_TRADING"
    private const val DISABLE_ANIMAL_PROTECTION = "DISABLE_ANIMAL_PROTECTION"
    private const val DISABLE_PLAYER_FALL_DAMAGE = "DISABLE_PLAYER_FALL_DAMAGE"
    private const val DISABLE_PUBLIC_REDSTONE_TRIGGERS = "DISABLE_PUBLIC_REDSTONE_TRIGGERS"
    private const val DISABLE_WEIGHTED_PRESSURE_PLATES = "DISABLE_WEIGHTED_PRESSURE_PLATES"
    private const val DISABLE_GATES = "DISABLE_GATES"
    private const val REFUSAL_MESSAGE_COOLDOWN_TICKS = 20L

    /** The region each player was standing in when they opened their container. */
    private val containerRegions = HashMap<UUID, Region>()

    /**
     * Last refusal-message tick per player and live region identity. Identity
     * keys keep distinct region objects independent even if they compare equal.
     */
    private val refusalMessageTicks = HashMap<UUID, IdentityHashMap<Region, Long>>()

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
     *
     * A predicate list rather than a direct `CrystalItem.isCrystal` call here,
     * even though the crystal is the only registrant today, because the arrow
     * only points one way: `crystal` is built on `region`, and naming the
     * crystal here would turn that into a cycle and leave region protection
     * unusable without the feature it is supposed to be independent of. The
     * list is the seam that keeps this file's contract general — "a feature may
     * own items this does not touch" — rather than a list of one feature's
     * names.
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
    /** Whether [menu] is a personal ender-chest menu, rather than a world chest. */
    @JvmStatic
    fun isPersonalEnderChestMenu(menu: AbstractContainerMenu): Boolean =
        menu is ChestMenu && menu.container is PlayerEnderChestContainer


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
        AttackBlockCallback.EVENT.reloadable.register { player, level, _, pos, _ ->
            allowedOrFail(player !is ServerPlayer || allowsBlockChange(player, level, pos))
        }
        // The authoritative refusal: whatever route reached "this block is
        // about to break", it does not break.
        PlayerBlockBreakEvents.BEFORE.reloadable.register { level, player, pos, _, _ ->
            player !is ServerPlayer || allowsBlockChange(player, level, pos)
        }

        // ---- building ----
        // An item *acting on* a block: placing, tilling, striking a light,
        // scooping with a bucket. This event fires only after the block itself
        // declined the click, so its firing already means the item is about to
        // change something — [RegionInteractables.isRegionModifyingItem] says
        // which items those are, and every other item (a sword, a spyglass, a
        // stick) passes with no message. The block's own right-click behaviour
        // is [UseBlockCallback] below.
        ItemEvents.USE_ON.reloadable.register { context ->
            val player = context.player
            val modifies =
                RegionInteractables.isRegionModifyingItem(context.itemInHand) ||
                    exemptUseChangesBlock(context)
            if (player is ServerPlayer &&
                modifies &&
                !allowsBlockChange(player, context.level, context.clickedPos)
            ) {
                InteractionResult.FAIL
            } else {
                null
            }
        }

        // ---- the block's own right-click behaviour ----
        // Fires for every block right-click before vanilla decides whether the
        // block or the item in hand handles it, so one hook covers the
        // workstation that opens, the furnace a stranger may look inside, the
        // composter that fills, the note block that retunes, and the door the
        // DISABLE_ flags close. [allowsBlockInteract] sorts them (issue #40).
        UseBlockCallback.EVENT.reloadable.register { player, level, hand, hit ->
            allowedOrFail(
                player !is ServerPlayer ||
                    allowsBlockInteract(
                        player,
                        level,
                        hit.blockPos,
                        level.getBlockState(hit.blockPos),
                        player.getItemInHand(hand),
                    ),
            )
        }

        // ---- fall damage ----
        ServerLivingEntityEvents.ALLOW_DAMAGE.reloadable.register { entity, source, _ ->
            entity !is ServerPlayer ||
                !source.`is`(DamageTypeTags.IS_FALL) ||
                allowsFallDamage(entity)
        }

        // Mob melee calls LivingEntity.hurtServer directly instead of
        // Entity.hurtOrSimulate, so protect living targets at Fabric's shared
        // server-damage seam as well.
        ServerLivingEntityEvents.ALLOW_DAMAGE.reloadable.register { entity, source, _ ->
            allowsEntityDamage(entity, source)
        }

        // ---- item use ----
        UseItemCallback.EVENT.reloadable.register { player, _, hand ->
            allowedOrFail(player !is ServerPlayer || allowsItemUse(player, player.getItemInHand(hand)))
        }

        // ---- entities ----
        AttackEntityCallback.EVENT.reloadable.register { player, _, _, entity, _ ->
            allowedOrFail(player !is ServerPlayer || allowsEntityAttack(player, entity))
        }
        UseEntityCallback.EVENT.reloadable.register { player, _, hand, entity, _ ->
            allowedOrFail(player !is ServerPlayer || allowsEntityInteract(player, hand, entity))
        }

        ServerPlayerEvents.LEAVE.reloadable.register { player ->
            containerRegions.remove(player.uuid)
            refusalMessageTicks.remove(player.uuid)
        }
        ServerPlayConnectionEvents.DISCONNECT.reloadable.register { handler, _ ->
            containerRegions.remove(handler.player.uuid)
            refusalMessageTicks.remove(handler.player.uuid)
        }
        ServerLifecycleEvents.SERVER_STOPPED.reloadable.register {
            containerRegions.clear()
            refusalMessageTicks.clear()
        }

    }

    /**
     * Whether [player] may change the block at [pos] — digging, building, and
     * editing a sign all ask this of the *target block's* region, not the one
     * the player is standing in. A false answer always denies; its refusal
     * message may be throttled.
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
     * to the public. A false answer always denies; its refusal message may be
     * throttled.
     */
    @JvmStatic
    fun allowsContainerUse(player: ServerPlayer): Boolean {
        val region = containerRegions[player.uuid] ?: return true
        if (canModifyRegion(player, region) || ENABLE_PUBLIC_CONTAINERS in region.flags) return true
        return refuse(player, region)
    }

    /**
     * Whether [player] may right-click the block at [pos] the way [state]
     * responds to it — open the workstation, ring the bell, fill the composter,
     * retune the note block, open the door. [held] is the item in the acting
     * hand, empty for the without-item path; it only matters at a campfire,
     * where a non-member may set food to cook but not douse or relight it.
     *
     * Two things can refuse a non-member here:
     *
     * - the `DISABLE_GATES` / `DISABLE_PUBLIC_REDSTONE_TRIGGERS` flags, exactly
     *   as before — the doors and the switches a region owner asks to be
     *   closed;
     * - the interaction class (issue #40): a block that changes the region's
     *   contents (`REQUIRES_MEMBERSHIP`) is refused, a workstation or a
     *   container a non-member may open to look inside (`FREE`,
     *   `CONTAINER_VIEW`) is not — the container's slot clicks are still
     *   refused by `RegionContainerClickMixin`.
     *
     * A false answer always denies; its refusal message may be throttled.
     */
    private fun allowsBlockInteract(
        player: ServerPlayer,
        level: Level,
        pos: BlockPos,
        state: BlockState,
        held: ItemStack,
    ): Boolean {
        restrictingFlagFor(state)?.let { flag ->
            regionRefusing(player, level, pos, flag)?.let { return refuse(player, it) }
        }

        val region = RegionsFeature.regionAt(level, pos) ?: return true
        if (canModifyRegion(player, region)) return true

        // A feature that owns its own right-click (the Teleportation Crystal)
        // opens over any block; its handler runs after this one and would never
        // be reached if the block under the cursor happened to be one this
        // refuses.
        if (isExemptItem(held)) return true

        return when (RegionInteractables.classify(state)) {
            RegionInteractables.BlockUse.FREE,
            RegionInteractables.BlockUse.CONTAINER_VIEW,
            -> true

            RegionInteractables.BlockUse.REQUIRES_MEMBERSHIP -> refuse(player, region)

            RegionInteractables.BlockUse.CAMPFIRE ->
                if (RegionInteractables.changesCampfire(held)) refuse(player, region) else true
        }
    }

    /**
     * Whether [entity] may set off the pressure plate at [pos].
     *
     * Ordinary plates use `DISABLE_PUBLIC_REDSTONE_TRIGGERS`: non-members are
     * ignored while residents and non-player entities still work the plate.
     * Weighted plates are automation rather than membership checks, so they
     * work for every entity by default and are disabled wholesale only by
     * `DISABLE_WEIGHTED_PRESSURE_PLATES`.
     */
    @JvmStatic
    fun allowsPressurePlate(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
    ): Boolean {
        val region = RegionsFeature.regionAt(level, pos) ?: return true
        if (state.block is WeightedPressurePlateBlock) {
            return DISABLE_WEIGHTED_PRESSURE_PLATES !in region.flags
        }
        val player = entity as? ServerPlayer ?: return true
        return canModifyRegion(player, region) || DISABLE_PUBLIC_REDSTONE_TRIGGERS !in region.flags
    }

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
     * exempt item is nobody's business but its own. A false answer always denies;
     * its refusal message may be throttled.
     */
    @JvmStatic
    fun allowsItemUse(player: ServerPlayer?, stack: ItemStack): Boolean {
        if (isItemUseExempt(stack)) return true
        val p = player ?: return true
        val region = RegionTracker.regionOf(p) ?: return true
        return canModifyRegion(p, region) || refuse(p, region)
    }

    /** Items whose use is independent of the block or region being looked at. */
    private fun isItemUseExempt(stack: ItemStack): Boolean =
        stack.isEmpty ||
            isExemptItem(stack) ||
            stack.has(DataComponents.FOOD) ||
            stack.has(DataComponents.POTION_CONTENTS) ||
            stack.`is`(Items.POTION) ||
            stack.`is`(Items.MILK_BUCKET) ||
            stack.`is`(Items.HONEY_BOTTLE) ||
            stack.`is`(Items.GOLDEN_APPLE) ||
            stack.`is`(Items.ENCHANTED_GOLDEN_APPLE) ||
            stack.`is`(Items.FIREWORK_ROCKET) ||
            stack.`is`(Items.ENDER_PEARL) ||
            stack.`is`(Items.ENDER_EYE)

    /** Block-changing use-on paths hidden inside otherwise safe air-use items. */
    private fun exemptUseChangesBlock(context: UseOnContext): Boolean {
        val stack = context.itemInHand
        val state = context.level.getBlockState(context.clickedPos)
        if (stack.`is`(Items.ENDER_EYE)) {
            return state.block is EndPortalFrameBlock && !state.getValue(EndPortalFrameBlock.HAS_EYE)
        }
        if (!stack.`is`(Items.POTION) ||
            context.clickedFace == Direction.DOWN ||
            !state.`is`(BlockTags.CONVERTABLE_TO_MUD)
        ) {
            return false
        }
        val contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
        return contents.`is`(Potions.WATER)
    }

    /** Hitting [entity] is refused in its deepest target region, for every entity type. */
    @JvmStatic
    fun allowsEntityAttack(player: ServerPlayer?, entity: Entity?): Boolean {
        val p = player ?: return true
        val region = entityProtectionAround(p, entity) ?: return true
        return refuse(p, region)
    }

    /**
     * Whether [source] may damage [entity].
     *
     * Projectile damage already resolves [DamageSource.entity] to its shooter.
     * A tamed animal is the source itself, however, so resolve its root owner
     * before applying the usual player-versus-target-region rule.
     */
    @JvmStatic
    fun allowsEntityDamage(entity: Entity, source: DamageSource): Boolean {
        val player = playerResponsibleFor(source) ?: return true
        val region = entityProtectionAround(player, entity) ?: return true
        return refuse(player, region)
    }

    private fun playerResponsibleFor(source: DamageSource): ServerPlayer? {
        val cause = source.entity
        return cause as? ServerPlayer ?: (cause as? OwnableEntity)?.rootOwner as? ServerPlayer
    }

    /**
     * Right-clicking [entity] is judged by the deepest region at that target's
     * block position. Empty-hand interaction remains allowed for ordinary
     * entities, while item frames and armor stands remain protected.
     * `ENABLE_PUBLIC_VILLAGER_TRADING` opens held-item interaction too.
     */
    @JvmStatic
    fun allowsEntityInteract(
        player: ServerPlayer?,
        hand: InteractionHand,
        entity: Entity?
    ): Boolean {
        val p = player ?: return true
        val region = entityProtectionAround(p, entity) ?: return true
        if (entity is ItemFrame || entity is ArmorStand) return refuse(p, region)
        if (p.getItemInHand(hand).isEmpty) return true
        if (ENABLE_PUBLIC_VILLAGER_TRADING in region.flags) return true
        return refuse(p, region)
    }

    /**
     * The deepest target region whose entities [player] must leave alone, or
     * null when they may do as they like — outside a region, inside one they
     * can modify, or in one flying `DISABLE_ANIMAL_PROTECTION`.
     */
    private fun entityProtectionAround(player: ServerPlayer, entity: Entity?): Region? {
        if (entity == null) return null
        val region = RegionsFeature.regionAt(entity.level(), entity.blockPosition()) ?: return null
        if (canModifyRegion(player, region)) return null
        if (DISABLE_ANIMAL_PROTECTION in region.flags) return null
        return region
    }

    /** Sends the Portal's refusal message when its cooldown permits, and always answers "not allowed". */
    private fun refuse(player: ServerPlayer, region: Region): Boolean {
        val now = player.level().server.tickCount.toLong()
        val byRegion = refusalMessageTicks.getOrPut(player.uuid) { IdentityHashMap() }
        val lastSent = byRegion[region]
        if (lastSent == null || now - lastSent >= REFUSAL_MESSAGE_COOLDOWN_TICKS) {
            player.sendSystemMessage(Paint.error("This area is protected by ", Paint.red(region.title)), true)
            byRegion[region] = now
        }
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
