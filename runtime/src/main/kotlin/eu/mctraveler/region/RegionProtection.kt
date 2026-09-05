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
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.tags.BlockTags
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.animal.camel.Camel
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse
import net.minecraft.world.entity.animal.equine.AbstractHorse
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.BlockAttachedEntity
import net.minecraft.world.entity.npc.villager.AbstractVillager
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat
import net.minecraft.world.entity.vehicle.minecart.Minecart
import net.minecraft.world.CompoundContainer
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MerchantMenu
import net.minecraft.world.inventory.PlayerEnderChestContainer
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.item.BoatItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MinecartItem
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
 * (spec User Story 36's flags — see [RegionFlags]).
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

    // Uniform presence semantics (RegionFlags): every flag here reads as
    // "<ID> in region.flags" means allowed, full stop. A flag's default is
    // decided once, by whether RegionFlags.Definition.defaultAllowed seeds it
    // at region creation — nothing below branches on a flag's own polarity.
    private const val PUBLIC = "PUBLIC"
    private const val PUBLIC_CHESTS = "PUBLIC_CHESTS"
    private const val PUBLIC_VILLAGERS = "PUBLIC_VILLAGERS"
    private const val ANIMAL_PROTECTION = "ANIMAL_PROTECTION"
    private const val FALL_DAMAGE = "FALL_DAMAGE"
    private const val PUBLIC_REDSTONE = "PUBLIC_REDSTONE"
    private const val WEIGHTED_PRESSURE_PLATES = "WEIGHTED_PRESSURE_PLATES"
    private const val GATES = "GATES"
    private const val DOORS = "DOORS"
    private const val TRAPDOORS = "TRAPDOORS"
    private const val BOATS = "BOATS"
    private const val MINECARTS = "MINECARTS"
    private const val RIDEABLE = "RIDEABLE"
    private const val PVP = "PVP"
    private const val REFUSAL_MESSAGE_COOLDOWN_TICKS = 20L

    /** The region a player's currently-open container is judged against. */
    private val containerRegions = HashMap<UUID, Region>()

    /**
     * The region under the block a player last right-clicked, and the tick they
     * did it. A container that opens from a block is judged by *that block's*
     * region, not the player's feet — so a stranger reaching a chest from
     * outside the region is still refused (the Portal captured the feet region
     * and so had this gap).
     */
    private val pendingContainerRegion = HashMap<UUID, Pair<Region, Long>>()

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

    /**
     * Whether [menu] is a real world chest (single or double, plain or
     * trapped) — the only container `PUBLIC_CHESTS` opens to non-members.
     *
     * Barrels also draw a [ChestMenu], but their container is a
     * `BarrelBlockEntity` rather than a [ChestBlockEntity] / [CompoundContainer],
     * so they are left out here: a barrel stays members-only like every other
     * non-chest container.
     */
    private fun isPublicChestMenu(menu: AbstractContainerMenu): Boolean =
        menu is ChestMenu &&
            menu.container !is PlayerEnderChestContainer &&
            (menu.container is ChestBlockEntity || menu.container is CompoundContainer)


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
            if (player !is ServerPlayer) {
                null
            } else if (context.itemInHand.item is MinecartItem) {
                // A minecart is placed on the rail it is used on — an entity,
                // not a block, so it answers to MINECARTS rather than the
                // plain membership gate every other placement uses.
                if (!allowsVehiclePlacement(player, context.level, context.clickedPos, MINECARTS)) {
                    resyncInventory(player)
                    InteractionResult.FAIL
                } else {
                    null
                }
            } else {
                val modifies =
                    RegionInteractables.isRegionModifyingItem(context.itemInHand) ||
                        exemptUseChangesBlock(context)
                if (modifies && !allowsBlockChange(player, context.level, context.clickedPos)) {
                    resyncInventory(player)
                    InteractionResult.FAIL
                } else {
                    null
                }
            }
        }

        // ---- the block's own right-click behaviour ----
        // Fires for every block right-click before vanilla decides whether the
        // block or the item in hand handles it, so one hook covers the
        // workstation that opens, the furnace a stranger may look inside, the
        // composter that fills, the note block that retunes, and the door,
        // gate or trapdoor a flag closes. [allowsBlockInteract] sorts them
        // (issue #40).
        UseBlockCallback.EVENT.reloadable.register { player, level, hand, hit ->
            if (player !is ServerPlayer) {
                InteractionResult.PASS
            } else {
                // A container that opens from this click is judged by the
                // clicked block's region, wherever the player is standing.
                rememberContainerBlock(player, level, hit.blockPos)
                if (!allowsBlockInteract(
                        player,
                        level,
                        hit.blockPos,
                        level.getBlockState(hit.blockPos),
                        player.getItemInHand(hand),
                    )
                ) {
                    resyncInventory(player)
                    InteractionResult.FAIL
                } else {
                    InteractionResult.PASS
                }
            }
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
            // A refused entity right-click (fuelling a furnace minecart, milking
            // a cow) leaves a ghost stack the client already moved, exactly like
            // a refused block right-click — so resync the inventory on refusal,
            // the way the block hooks do.
            if (player !is ServerPlayer || allowsEntityInteract(player, hand, entity)) {
                InteractionResult.PASS
            } else {
                resyncInventory(player)
                InteractionResult.FAIL
            }
        }

        ServerPlayerEvents.LEAVE.reloadable.register { player ->
            containerRegions.remove(player.uuid)
            pendingContainerRegion.remove(player.uuid)
            refusalMessageTicks.remove(player.uuid)
        }
        ServerPlayConnectionEvents.DISCONNECT.reloadable.register { handler, _ ->
            containerRegions.remove(handler.player.uuid)
            pendingContainerRegion.remove(handler.player.uuid)
            refusalMessageTicks.remove(handler.player.uuid)
        }
        ServerLifecycleEvents.SERVER_STOPPED.reloadable.register {
            containerRegions.clear()
            pendingContainerRegion.clear()
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
     * Whether [projectile] may pop the chorus flower it just hit
     * (`ChorusFlowerBlock.onProjectileHit`, a real vanilla mechanic every
     * arrow, trident, firework, snowball, egg, fireball and wind charge
     * triggers unconditionally) — a protection gap this closes rather than a
     * new rule: it is the ordinary block-change gate, reached from a
     * projectile's block hit instead of a player's own click, so it already
     * respects `PUBLIC` and membership exactly as every other block-change
     * rule does.
     *
     * A projectile with no player behind it (a dispenser-fired arrow, say) is
     * unaffected, matching this file's usual pattern for non-player causes.
     */
    @JvmStatic
    fun allowsProjectileBlockChange(projectile: Projectile, level: Level, pos: BlockPos): Boolean {
        val shooter = projectile.owner as? ServerPlayer ?: return true
        return allowsBlockChange(shooter, level, pos)
    }

    /**
     * Remembers the region a container [player] just opened is judged against,
     * and lets it govern the whole session — stepping outside, or a region
     * appearing, mid-session cannot change what the open chest allows (the
     * Portal captured the session at open time for the same reason).
     *
     * The region is the one under the *block the container is* when the open
     * followed a block right-click ([rememberContainerBlock]); only a menu with
     * no such block behind it (rare) falls back to the player's feet.
     */
    @JvmStatic
    fun containerOpened(player: ServerPlayer) {
        val region = containerRegionFor(player)
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
     * The title the client should show for a container [menu] [player] is
     * opening: `Region protected` (grey, bold) when it is a container in a
     * region they cannot modify, the [default] otherwise. Workstations and the
     * mod's own menus keep their name — a non-member uses those freely.
     */
    @JvmStatic
    fun containerTitleFor(player: ServerPlayer, menu: AbstractContainerMenu, default: Component): Component {
        if (menu === player.inventoryMenu ||
            isModOwnedMenu(menu) ||
            isPersonalEnderChestMenu(menu) ||
            RegionInteractables.isWorkstationMenu(menu)
        ) {
            return default
        }
        // A villager's trade screen keeps its name — viewing the trades is
        // allowed for anyone (PUBLIC_VILLAGERS only gates completing a trade).
        if (menu is MerchantMenu) return default
        val region = containerRegionFor(player) ?: return default
        if (canModifyRegion(player, region)) return default
        if (PUBLIC_CHESTS in region.flags && isPublicChestMenu(menu)) return default
        return Component.literal("Region protected").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD)
    }

    /** Records the region under the block [player] just right-clicked. */
    private fun rememberContainerBlock(player: ServerPlayer, level: Level, pos: BlockPos) {
        val region = RegionsFeature.regionAt(level, pos)
        if (region == null) {
            pendingContainerRegion.remove(player.uuid)
        } else {
            pendingContainerRegion[player.uuid] = region to player.level().server.tickCount.toLong()
        }
    }

    /**
     * The region an opening container is judged against — the block behind it
     * if the open just followed a right-click on that block this tick, else the
     * region the player is standing in.
     */
    private fun containerRegionFor(player: ServerPlayer): Region? {
        val pending = pendingContainerRegion[player.uuid]
        if (pending != null && player.level().server.tickCount - pending.second <= 1L) {
            return pending.first
        }
        return RegionTracker.regionOf(player)
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
        if (canModifyRegion(player, region)) return true
        if (PUBLIC_CHESTS in region.flags && isPublicChestMenu(player.containerMenu)) return true
        return refuse(player, region)
    }

    /**
     * Whether [player] may complete a villager trade (take the result item) in
     * the region they opened the merchant screen in — residents and `PUBLIC`
     * always, anyone else only while the region flies `PUBLIC_VILLAGERS`.
     * Viewing the trades is never refused ([allowsEntityInteract] lets an
     * empty-hand click open the screen); this gates only the payout.
     */
    @JvmStatic
    fun allowsVillagerTrade(player: ServerPlayer): Boolean {
        val region = containerRegions[player.uuid] ?: return true
        if (canModifyRegion(player, region) || PUBLIC_VILLAGERS in region.flags) return true
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
     * - the `GATES` / `DOORS` / `TRAPDOORS` / `PUBLIC_REDSTONE` flags, exactly
     *   as before — the gates, the doors, the trapdoors and the switches a
     *   region owner asks to be closed;
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
     * Ordinary plates use `PUBLIC_REDSTONE`: non-members are ignored unless it
     * is on, while residents and non-player entities still work the plate.
     * Weighted plates are automation rather than membership checks, so they
     * work for every entity by default and are disabled wholesale only by
     * turning `WEIGHTED_PRESSURE_PLATES` off.
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
            return WEIGHTED_PRESSURE_PLATES in region.flags
        }
        val player = entity as? ServerPlayer ?: return true
        return canModifyRegion(player, region) || PUBLIC_REDSTONE in region.flags
    }

    /** Which flag, if any, can take this block's own behaviour away from a stranger. */
    private fun restrictingFlagFor(state: BlockState): String? = when (state.block) {
        is DoorBlock -> DOORS
        is FenceGateBlock -> GATES
        is TrapDoorBlock -> TRAPDOORS
        is ButtonBlock, is LeverBlock -> PUBLIC_REDSTONE
        else -> null
    }

    /**
     * The region at [pos] that refuses [player] because it lacks [flag], or
     * null — no region, a member, or the flag is on.
     */
    private fun regionRefusing(player: ServerPlayer, level: Level, pos: BlockPos, flag: String): Region? {
        val region = RegionsFeature.regionAt(level, pos) ?: return null
        if (canModifyRegion(player, region)) return null
        return if (flag !in region.flags) region else null
    }

    /**
     * Whether a fall may hurt [player] where they are standing.
     *
     * `FALL_DAMAGE` names the *protection*, not the damage: its presence
     * means a region catches everyone inside it, member or not, because it is
     * the ground that is soft — so a fall is only allowed to hurt when the
     * flag is *absent*. Seeded present by default (`RegionFlags.FALL_DAMAGE`'s
     * `defaultAllowed = true`), matching this rework's chosen default of
     * fall-damage-protected rather than the Portal's old opt-in.
     */
    private fun allowsFallDamage(player: ServerPlayer): Boolean {
        val region = RegionTracker.regionOf(player) ?: return true
        return FALL_DAMAGE !in region.flags
    }

    /**
     * Whether [player] may use [stack] in the air where they stand (issue #40).
     *
     * Almost everything may. An item used through `Item.use` rather than on a
     * block raises a spyglass, reads a map, sounds a goat horn, throws a pearl
     * or a snowball, casts a line, boosts an elytra, eats — none of it touches
     * the region, so none of it earns a refusal. The one exception is a bucket,
     * whose use places or lifts a fluid: [RegionBucketMixin] judges that by the
     * block the fluid reaches, and this refuses a non-member starting one while
     * they stand on land they cannot modify.
     *
     * A false answer always denies; its refusal message may be throttled.
     */
    @JvmStatic
    fun allowsItemUse(player: ServerPlayer?, stack: ItemStack): Boolean {
        if (stack.isEmpty || isExemptItem(stack)) return true
        val p = player ?: return true
        if (stack.item is BoatItem) {
            val region = RegionTracker.regionOf(p) ?: return true
            return canModifyRegion(p, region) || BOATS in region.flags || refuse(p, region)
        }
        if (stack.item !is BucketItem) return true
        val region = RegionTracker.regionOf(p) ?: return true
        return canModifyRegion(p, region) || refuse(p, region)
    }

    /** Whether [player] may place a minecart on the rail at [pos], gated by [flag] (`MINECARTS`). */
    private fun allowsVehiclePlacement(player: ServerPlayer, level: Level, pos: BlockPos, flag: String): Boolean {
        val region = RegionsFeature.regionAt(level, pos) ?: return true
        return canModifyRegion(player, region) || flag in region.flags || refuse(player, region)
    }

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

    /**
     * Hitting [entity] is refused in its deepest target region — except an
     * un-named hostile mob, which a non-member may cull (issue #40). A name tag
     * makes even a hostile someone's, and so protected; item frames and armor
     * stands are always protected. A boat or an empty minecart is not an
     * animal, so it answers to its own `BOATS`/`MINECARTS` flag instead of
     * `ANIMAL_PROTECTION`.
     */
    @JvmStatic
    fun allowsEntityAttack(player: ServerPlayer?, entity: Entity?): Boolean {
        val p = player ?: return true
        if (entity is ServerPlayer) return allowsPvp(p, entity)
        if (entity != null) {
            vehicleFlagFor(entity)?.let { flag -> return allowsVehicleUse(p, entity, flag) }
            alwaysProtectedRegionOf(entity)?.let { region ->
                return canModifyRegion(p, region) || refuse(p, region)
            }
        }
        val region = entityProtectionAround(p, entity) ?: return true
        if (isCullableHostile(entity)) return true
        return refuse(p, region)
    }

    /**
     * The region protecting [entity] regardless of any flag — a villager or a
     * chest boat, which no `ANIMAL_PROTECTION`/`BOATS` toggle ever exposes to a
     * non-member. Null when [entity] is neither, or stands on unclaimed ground.
     */
    private fun alwaysProtectedRegionOf(entity: Entity): Region? {
        if (entity !is AbstractVillager && entity !is AbstractChestBoat) return null
        return RegionsFeature.regionAt(entity.level(), entity.blockPosition())
    }

    /** An unnamed hostile: killable by anyone, even inside a region. */
    private fun isCullableHostile(entity: Entity?): Boolean =
        entity is Enemy && entity !is ArmorStand && !entity.hasCustomName()

    /**
     * Whether [source] may damage [entity].
     *
     * Projectile damage already resolves [DamageSource.entity] to its shooter.
     * A tamed animal is the source itself, however, so resolve its root owner
     * before applying the usual player-versus-target-region rule.
     */
    @JvmStatic
    fun allowsEntityDamage(entity: Entity, source: DamageSource): Boolean {
        if (isRegionDecoration(entity)) return allowsDecorationDamage(entity, source)
        val player = playerResponsibleFor(source) ?: return true
        if (entity is ServerPlayer) return allowsPvp(player, entity)
        vehicleFlagFor(entity)?.let { flag -> return allowsVehicleUse(player, entity, flag) }
        alwaysProtectedRegionOf(entity)?.let { region ->
            return canModifyRegion(player, region) || refuse(player, region)
        }
        val region = entityProtectionAround(player, entity) ?: return true
        if (isCullableHostile(entity)) return true
        return refuse(player, region)
    }

    /**
     * PVP is allowed inside a region by default (issue request) — only a
     * region without `PVP` is a safe zone, and it is one for everyone standing
     * in it, [victim]'s own membership included: the point of the flag is "no
     * fighting here," not a members-only exemption.
     */
    private fun allowsPvp(attacker: ServerPlayer, victim: ServerPlayer): Boolean {
        if (attacker === victim) return true
        val region = RegionsFeature.regionAt(victim.level(), victim.blockPosition()) ?: return true
        if (PVP in region.flags) return true
        return refuse(attacker, region)
    }

    /**
     * The flag that gates breaking or damaging [entity] as a vehicle rather
     * than as an animal — a boat, a chest boat, or a plain minecart carrying
     * no mob passenger (a chest/furnace/TNT/hopper/command-block/spawner
     * minecart, or one currently carrying a mob, stays under the
     * unconditional decoration-style protection those already have; only an
     * otherwise-empty rideable minecart is gated here). Null for anything
     * else, which falls through to [entityProtectionAround]'s ordinary rule.
     */
    private fun vehicleFlagFor(entity: Entity): String? = when {
        // A chest boat is never gated by BOATS — it is always protected, via
        // [alwaysProtectedRegionOf], the way a decoration is.
        entity is AbstractChestBoat -> null
        entity is AbstractBoat -> BOATS
        entity is Minecart && entity.passengers.none { it is Mob } -> MINECARTS
        else -> null
    }

    /** Whether [player] may break or damage the vehicle [entity], gated by [flag]. */
    private fun allowsVehicleUse(player: ServerPlayer, entity: Entity, flag: String): Boolean {
        val region = RegionsFeature.regionAt(entity.level(), entity.blockPosition()) ?: return true
        if (canModifyRegion(player, region)) return true
        if (flag in region.flags) return true
        return refuse(player, region)
    }

    /**
     * An item frame, a painting, an armor stand — a placed decoration a region
     * makes indestructible: nothing inside a region breaks one, not a creeper,
     * not a skeleton's arrow, not a stranger, only a member removing it. There
     * is no flag and no message; the hit is simply ignored.
     */
    private fun isRegionDecoration(entity: Entity): Boolean =
        entity is BlockAttachedEntity || entity is ArmorStand

    private fun allowsDecorationDamage(entity: Entity, source: DamageSource): Boolean {
        val pos = (entity as? BlockAttachedEntity)?.getPos() ?: entity.blockPosition()
        val region = RegionsFeature.regionAt(entity.level(), pos) ?: return true
        val by = playerResponsibleFor(source)
        return by != null && canModifyRegion(by, region)
    }

    private fun playerResponsibleFor(source: DamageSource): ServerPlayer? {
        val cause = source.entity
        return cause as? ServerPlayer ?: (cause as? OwnableEntity)?.rootOwner as? ServerPlayer
    }

    /**
     * Right-clicking [entity] is judged by the deepest region at that target's
     * block position (issue #40):
     *
     * - item frames and armor stands are always protected;
     * - a chested donkey or mule opens its inventory to a crouch-click with an
     *   empty hand — view-only, the container mixin refuses the slot clicks —
     *   and refuses everything else (it is not ridden, its load not touched);
     * - any other rideable mount (horse-family, camel, pig, nautilus) may be
     *   mounted with an empty hand, gated by `RIDEABLE`;
     * - every other mob keeps the old rule: an empty-hand interaction that
     *   changes nothing is allowed, a held-item one is refused unless the
     *   region flies `PUBLIC_VILLAGERS`.
     */
    @JvmStatic
    fun allowsEntityInteract(
        player: ServerPlayer?,
        hand: InteractionHand,
        entity: Entity?
    ): Boolean {
        val p = player ?: return true

        // Villagers and chest boats are protected regardless of ANIMAL_PROTECTION
        // (which entityProtectionAround honours), so they are handled before it.
        if (entity is AbstractVillager) {
            val r = RegionsFeature.regionAt(entity.level(), entity.blockPosition())
            if (r == null || canModifyRegion(p, r)) return true
            // An empty hand opens the trade screen so a non-member may LOOK;
            // completing a trade is gated separately by allowsVillagerTrade.
            if (p.getItemInHand(hand).isEmpty) return true
            return if (PUBLIC_VILLAGERS in r.flags) true else refuse(p, r)
        }
        if (entity is AbstractChestBoat) {
            val r = RegionsFeature.regionAt(entity.level(), entity.blockPosition())
            return r == null || canModifyRegion(p, r) || refuse(p, r)
        }

        val region = entityProtectionAround(p, entity) ?: return true
        if (entity != null && isRegionDecoration(entity)) return refuse(p, region)

        val emptyHand = p.getItemInHand(hand).isEmpty
        if (entity is AbstractChestedHorse && entity.hasChest()) {
            if (emptyHand && p.isShiftKeyDown) {
                rememberContainerBlock(p, entity.level(), entity.blockPosition())
                return true
            }
            return refuse(p, region)
        }
        // A leashed rideable mount is off-limits to a passing non-member whether
        // or not they hold something — neither RIDEABLE nor PUBLIC_VILLAGERS'
        // held-item opening hands over a mount someone else has on a lead.
        if (entity != null && isRideableMount(entity) && entity is Mob && entity.isLeashed) {
            return refuse(p, region)
        }
        if (entity != null && isRideableMount(entity) && emptyHand && !p.isShiftKeyDown) {
            if (RIDEABLE in region.flags) return true
            return refuse(p, region)
        }

        if (emptyHand) return true
        if (PUBLIC_VILLAGERS in region.flags) return true
        return refuse(p, region)
    }

    /** A mount a `RIDEABLE`-flying region may be ridden in: the horse family, camels, pigs, nautiluses. */
    private fun isRideableMount(entity: Entity): Boolean =
        entity is AbstractHorse || entity is Camel || entity is Pig || entity is AbstractNautilus

    /**
     * The deepest target region whose entities [player] must leave alone, or
     * null when they may do as they like — outside a region, inside one they
     * can modify, or in one without `ANIMAL_PROTECTION`.
     */
    private fun entityProtectionAround(player: ServerPlayer, entity: Entity?): Region? {
        if (entity == null) return null
        val region = RegionsFeature.regionAt(entity.level(), entity.blockPosition()) ?: return null
        if (canModifyRegion(player, region)) return null
        if (ANIMAL_PROTECTION !in region.flags) return null
        return region
    }

    /**
     * Re-sends the player's inventory after a refused right-click.
     *
     * The client runs the interaction locally before the server answers, so a
     * refused pot-a-flower or bottle-the-honey leaves a ghost stack in the
     * inventory — the item the client moved, that the server never did. Fabric
     * resyncs the *block* after a cancelled interaction but not the inventory;
     * this closes that gap.
     */
    private fun resyncInventory(player: ServerPlayer) {
        player.containerMenu.sendAllDataToRemote()
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
