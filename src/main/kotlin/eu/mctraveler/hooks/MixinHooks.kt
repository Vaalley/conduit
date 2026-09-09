package eu.mctraveler.hooks

import com.mojang.authlib.GameProfile
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.status.ServerStatus
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.CraftingContainer
import net.minecraft.world.inventory.ResultContainer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.portal.TeleportTransition

/** The seam the mixins call into the features through. */
interface MixinHooks {
    // Identity remaps
    fun isAliased(uuid: UUID): Boolean
    fun remapProfile(profile: GameProfile): GameProfile

    // Away
    fun onPlayerCommand(player: ServerPlayer)

    /** Issue #52: whether [player] is away and so excluded from the sleep-percentage maths. */
    fun isAway(player: ServerPlayer): Boolean

    // MOTD
    fun decorateStatus(status: ServerStatus, server: MinecraftServer): ServerStatus

    // Orphaned-save claim
    fun claimSaveBefore(nameAndId: NameAndId)
    fun loginDenial(nameAndId: NameAndId): Component?

    // Notepad
    fun notepadHasSession(player: ServerPlayer): Boolean
    fun notepadCompleteSession(player: ServerPlayer, pages: List<String>)
    fun notepadCancelSession(player: ServerPlayer)

    // Tab list
    /** Null means "leave vanilla's tab name alone". */
    fun tabDisplayName(player: ServerPlayer): Component?

    /** [packet] as [viewer] should receive it — [packet] itself when nothing about it is viewer-specific. */
    fun packetForViewer(viewer: ServerPlayer, packet: Packet<*>): Packet<*>

    /** Issue #47: whether [target]'s entity must be hidden from [viewer] right now (vanished). */
    fun isVanishedFromViewer(target: ServerPlayer, viewer: ServerPlayer): Boolean

    // Ranks
    /**
     * The markdown-parsed styled line for [raw], or null when [editor]'s rank
     * carries no markdown perk — meaning leave the vanilla-built line alone.
     */
    fun markdownLineFor(editor: ServerPlayer, raw: String): Component?

    /** Registers whether the chunk at [pos] holds a `<name>` sign (called once the sign is in-world). */
    fun onSignLoadedOrChanged(level: Level, pos: BlockPos, sign: SignBlockEntity)

    /** Recomputes the `<name>` registry for [pos]'s chunk after a sign is removed. */
    fun onSignRemoved(level: Level, pos: BlockPos)

    // Embassies
    fun beforeTeleport(player: ServerPlayer, destination: ResourceKey<Level>)

    // Dragon fight arenas
    fun isArena(level: ServerLevel): Boolean
    fun arenaExitPortal(level: ServerLevel, entity: Entity): TeleportTransition?
    fun arenaGatewayBlocked(level: ServerLevel, entity: Entity)

    // Crystal
    fun isCrystalAcceptCommand(command: String): Boolean
    fun crystalAccept(acceptor: ServerPlayer, command: String)
    fun crystalCraftingGuard(
        menu: AbstractContainerMenu,
        player: Player,
        craftSlots: CraftingContainer,
        resultSlots: ResultContainer,
    )

    // Region protection
    fun isModOwnedMenu(menu: AbstractContainerMenu): Boolean
    fun isPersonalEnderChestMenu(menu: AbstractContainerMenu): Boolean
    fun isWorkstationMenu(menu: AbstractContainerMenu): Boolean
    fun allowsContainerUse(player: ServerPlayer): Boolean
    fun allowsVillagerTrade(player: ServerPlayer): Boolean
    fun containerOpened(player: ServerPlayer)
    fun containerClosed(player: ServerPlayer)
    fun containerTitleFor(player: ServerPlayer, menu: AbstractContainerMenu, default: Component): Component
    fun allowsPressurePlate(state: BlockState, level: Level, pos: BlockPos, entity: Entity): Boolean
    fun allowsBlockChange(player: ServerPlayer, level: Level, pos: BlockPos): Boolean
    fun allowsEntityDamage(entity: Entity, source: DamageSource): Boolean
    fun allowsProjectileBlockChange(projectile: Projectile, level: Level, pos: BlockPos): Boolean

    // Region environment
    fun allowsCreatureBlockChange(level: Level, pos: BlockPos, creature: Entity?): Boolean
    fun allowsExplosionDamage(level: Level, pos: BlockPos): Boolean
    fun allowsFireDamage(level: Level, pos: BlockPos): Boolean
    fun allowsFluidSpread(level: Level, from: BlockPos, to: BlockPos): Boolean
    fun allowsDecorationMove(level: Level, pos: BlockPos): Boolean
    fun allowsExplosionEntityEffect(level: Level, source: Entity?, target: Entity): Boolean
    fun allowsPotionEffect(level: Level, thrower: Entity?, target: LivingEntity): Boolean
    fun allowsPistonMove(
        level: Level,
        pistonPos: BlockPos,
        headPos: BlockPos?,
        toPush: List<BlockPos>,
        toDestroy: List<BlockPos>,
        pushDirection: Direction,
    ): Boolean
}

/** The static facade the mixins call. */
object Hooks {
    @Volatile
    @JvmStatic
    var impl: MixinHooks? = null

    @JvmStatic fun isAliased(uuid: UUID): Boolean = impl?.isAliased(uuid) ?: false

    @JvmStatic fun remapProfile(profile: GameProfile): GameProfile = impl?.remapProfile(profile) ?: profile

    @JvmStatic fun onPlayerCommand(player: ServerPlayer) {
        impl?.onPlayerCommand(player)
    }

    @JvmStatic fun isAway(player: ServerPlayer): Boolean = impl?.isAway(player) ?: false

    @JvmStatic fun decorateStatus(status: ServerStatus, server: MinecraftServer): ServerStatus =
        impl?.decorateStatus(status, server) ?: status

    @JvmStatic fun claimSaveBefore(nameAndId: NameAndId) {
        impl?.claimSaveBefore(nameAndId)
    }

    @JvmStatic fun loginDenial(nameAndId: NameAndId): Component? =
        impl?.loginDenial(nameAndId)

    @JvmStatic fun notepadHasSession(player: ServerPlayer): Boolean = impl?.notepadHasSession(player) ?: false

    @JvmStatic fun notepadCompleteSession(player: ServerPlayer, pages: List<String>) {
        impl?.notepadCompleteSession(player, pages)
    }

    @JvmStatic fun notepadCancelSession(player: ServerPlayer) {
        impl?.notepadCancelSession(player)
    }

    @JvmStatic fun tabDisplayName(player: ServerPlayer): Component? = impl?.tabDisplayName(player)

    @JvmStatic fun packetForViewer(viewer: ServerPlayer, packet: Packet<*>): Packet<*> =
        impl?.packetForViewer(viewer, packet) ?: packet

    @JvmStatic fun isVanishedFromViewer(target: ServerPlayer, viewer: ServerPlayer): Boolean =
        impl?.isVanishedFromViewer(target, viewer) ?: false

    @JvmStatic fun markdownLineFor(editor: ServerPlayer, raw: String): Component? =
        impl?.markdownLineFor(editor, raw)

    @JvmStatic fun onSignLoadedOrChanged(level: Level, pos: BlockPos, sign: SignBlockEntity) {
        impl?.onSignLoadedOrChanged(level, pos, sign)
    }

    @JvmStatic fun onSignRemoved(level: Level, pos: BlockPos) {
        impl?.onSignRemoved(level, pos)
    }

    @JvmStatic fun beforeTeleport(player: ServerPlayer, destination: ResourceKey<Level>) {
        impl?.beforeTeleport(player, destination)
    }

    @JvmStatic fun isArena(level: ServerLevel): Boolean = impl?.isArena(level) ?: false

    @JvmStatic fun arenaExitPortal(level: ServerLevel, entity: Entity): TeleportTransition? =
        impl?.arenaExitPortal(level, entity)

    @JvmStatic fun arenaGatewayBlocked(level: ServerLevel, entity: Entity) {
        impl?.arenaGatewayBlocked(level, entity)
    }

    @JvmStatic fun isCrystalAcceptCommand(command: String): Boolean = impl?.isCrystalAcceptCommand(command) ?: false

    @JvmStatic fun crystalAccept(acceptor: ServerPlayer, command: String) {
        impl?.crystalAccept(acceptor, command)
    }

    @JvmStatic fun crystalCraftingGuard(
        menu: AbstractContainerMenu,
        player: Player,
        craftSlots: CraftingContainer,
        resultSlots: ResultContainer,
    ) {
        impl?.crystalCraftingGuard(menu, player, craftSlots, resultSlots)
    }


    @JvmStatic fun isModOwnedMenu(menu: AbstractContainerMenu): Boolean = impl?.isModOwnedMenu(menu) ?: false
    @JvmStatic fun isPersonalEnderChestMenu(menu: AbstractContainerMenu): Boolean =
        impl?.isPersonalEnderChestMenu(menu) ?: false
    @JvmStatic fun isWorkstationMenu(menu: AbstractContainerMenu): Boolean = impl?.isWorkstationMenu(menu) ?: false

    @JvmStatic fun allowsContainerUse(player: ServerPlayer): Boolean = impl?.allowsContainerUse(player) ?: true

    @JvmStatic fun allowsVillagerTrade(player: ServerPlayer): Boolean = impl?.allowsVillagerTrade(player) ?: true

    @JvmStatic fun containerOpened(player: ServerPlayer) {
        impl?.containerOpened(player)
    }

    @JvmStatic fun containerClosed(player: ServerPlayer) {
        impl?.containerClosed(player)
    }

    @JvmStatic fun containerTitleFor(player: ServerPlayer, menu: AbstractContainerMenu, default: Component): Component =
        impl?.containerTitleFor(player, menu, default) ?: default

    @JvmStatic fun allowsPressurePlate(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
    ): Boolean = impl?.allowsPressurePlate(state, level, pos, entity) ?: true

    @JvmStatic fun allowsEntityDamage(entity: Entity, source: DamageSource): Boolean =
        impl?.allowsEntityDamage(entity, source) ?: true

    @JvmStatic fun allowsBlockChange(player: ServerPlayer, level: Level, pos: BlockPos): Boolean =
        impl?.allowsBlockChange(player, level, pos) ?: true

    @JvmStatic fun allowsProjectileBlockChange(projectile: Projectile, level: Level, pos: BlockPos): Boolean =
        impl?.allowsProjectileBlockChange(projectile, level, pos) ?: true

    @JvmStatic fun allowsExplosionEntityEffect(level: Level, source: Entity?, target: Entity): Boolean =
        impl?.allowsExplosionEntityEffect(level, source, target) ?: true

    @JvmStatic fun allowsPotionEffect(level: Level, thrower: Entity?, target: LivingEntity): Boolean =
        impl?.allowsPotionEffect(level, thrower, target) ?: true

    @JvmStatic fun allowsCreatureBlockChange(level: Level, pos: BlockPos, creature: Entity?): Boolean =
        impl?.allowsCreatureBlockChange(level, pos, creature) ?: true

    @JvmStatic fun allowsExplosionDamage(level: Level, pos: BlockPos): Boolean =
        impl?.allowsExplosionDamage(level, pos) ?: true

    @JvmStatic fun allowsFireDamage(level: Level, pos: BlockPos): Boolean =
        impl?.allowsFireDamage(level, pos) ?: true

    @JvmStatic fun allowsFluidSpread(level: Level, from: BlockPos, to: BlockPos): Boolean =
        impl?.allowsFluidSpread(level, from, to) ?: true

    @JvmStatic fun allowsDecorationMove(level: Level, pos: BlockPos): Boolean =
        impl?.allowsDecorationMove(level, pos) ?: true

    @JvmStatic fun allowsPistonMove(
        level: Level,
        pistonPos: BlockPos,
        headPos: BlockPos?,
        toPush: List<BlockPos>,
        toDestroy: List<BlockPos>,
        pushDirection: Direction,
    ): Boolean = impl?.allowsPistonMove(level, pistonPos, headPos, toPush, toDestroy, pushDirection) ?: true
}
