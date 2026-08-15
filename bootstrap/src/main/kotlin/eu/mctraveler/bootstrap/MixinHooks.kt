package eu.mctraveler.bootstrap

import com.mojang.authlib.GameProfile
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.status.ServerStatus
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.CraftingContainer
import net.minecraft.world.inventory.ResultContainer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * Everything the (permanent, non-reloadable) mixins need from the (reloadable)
 * runtime. The runtime hands an implementation back from [ConduitRuntime.start];
 * between runtimes every method falls back to vanilla-neutral behaviour, so the
 * server keeps running plain-vanilla rather than crashing while a reload is in
 * flight or a runtime jar is missing.
 */
interface MixinHooks {
    // Identity remaps
    fun isAliased(uuid: UUID): Boolean
    fun remapProfile(profile: GameProfile): GameProfile

    // Away
    fun onPlayerCommand(player: ServerPlayer)

    // MOTD
    fun decorateStatus(status: ServerStatus, server: MinecraftServer): ServerStatus

    // Orphaned-save claim
    fun claimSaveBefore(nameAndId: NameAndId)

    // Notepad
    fun notepadHasSession(player: ServerPlayer): Boolean
    fun notepadCompleteSession(player: ServerPlayer, pages: List<String>)
    fun notepadCancelSession(player: ServerPlayer)

    // Tab list
    /** Null means "leave vanilla's tab name alone". */
    fun tabDisplayName(player: ServerPlayer): Component?

    /** Null means "send the packet unmasked". */
    fun maskSpectators(viewer: ServerPlayer, packet: ClientboundPlayerInfoUpdatePacket): ClientboundPlayerInfoUpdatePacket?

    // Embassies
    fun beforeTeleport(player: ServerPlayer, destination: ResourceKey<Level>)

    // Crystal
    fun isCrystalAcceptCommand(command: String): Boolean
    fun crystalAccept(acceptor: ServerPlayer, command: String)
    fun crystalCraftingGuard(
        menu: AbstractContainerMenu,
        player: Player,
        craftSlots: CraftingContainer,
        resultSlots: ResultContainer,
    )
    fun crystalDamageForViewer(viewer: ServerPlayer, packet: Packet<*>): Packet<*>

    // Region protection
    fun isModOwnedMenu(menu: AbstractContainerMenu): Boolean
    fun isPersonalEnderChestMenu(menu: AbstractContainerMenu): Boolean
    fun allowsContainerUse(player: ServerPlayer): Boolean
    fun containerOpened(player: ServerPlayer)
    fun containerClosed(player: ServerPlayer)
    fun allowsPressurePlate(state: BlockState, level: Level, pos: BlockPos, entity: Entity): Boolean
    fun allowsBlockChange(player: ServerPlayer, level: Level, pos: BlockPos): Boolean
    fun allowsEntityDamage(entity: Entity, source: DamageSource): Boolean

    // Region environment
    fun allowsCreatureBlockChange(level: Level, pos: BlockPos, creature: Entity?): Boolean
    fun allowsExplosionDamage(level: Level, pos: BlockPos): Boolean
    fun allowsFireDamage(level: Level, pos: BlockPos): Boolean
    fun allowsPistonMove(
        level: Level,
        pistonPos: BlockPos,
        headPos: BlockPos?,
        toPush: List<BlockPos>,
        toDestroy: List<BlockPos>,
        pushDirection: Direction,
    ): Boolean
}

/**
 * The static facade the mixins call. Mixins run from netty, auth, and server
 * threads; [impl] is volatile and every call falls back to a vanilla-neutral
 * default the instant the runtime is unloaded.
 */
object Hooks {
    @Volatile
    @JvmStatic
    var impl: MixinHooks? = null

    @JvmStatic fun isAliased(uuid: UUID): Boolean = impl?.isAliased(uuid) ?: false

    @JvmStatic fun remapProfile(profile: GameProfile): GameProfile = impl?.remapProfile(profile) ?: profile

    @JvmStatic fun onPlayerCommand(player: ServerPlayer) {
        impl?.onPlayerCommand(player)
    }

    @JvmStatic fun decorateStatus(status: ServerStatus, server: MinecraftServer): ServerStatus =
        impl?.decorateStatus(status, server) ?: status

    @JvmStatic fun claimSaveBefore(nameAndId: NameAndId) {
        impl?.claimSaveBefore(nameAndId)
    }

    @JvmStatic fun notepadHasSession(player: ServerPlayer): Boolean = impl?.notepadHasSession(player) ?: false

    @JvmStatic fun notepadCompleteSession(player: ServerPlayer, pages: List<String>) {
        impl?.notepadCompleteSession(player, pages)
    }

    @JvmStatic fun notepadCancelSession(player: ServerPlayer) {
        impl?.notepadCancelSession(player)
    }

    @JvmStatic fun tabDisplayName(player: ServerPlayer): Component? = impl?.tabDisplayName(player)

    @JvmStatic fun maskSpectators(
        viewer: ServerPlayer,
        packet: ClientboundPlayerInfoUpdatePacket,
    ): ClientboundPlayerInfoUpdatePacket? = impl?.maskSpectators(viewer, packet)

    @JvmStatic fun beforeTeleport(player: ServerPlayer, destination: ResourceKey<Level>) {
        impl?.beforeTeleport(player, destination)
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

    @JvmStatic fun crystalDamageForViewer(viewer: ServerPlayer, packet: Packet<*>): Packet<*> =
        impl?.crystalDamageForViewer(viewer, packet) ?: packet

    @JvmStatic fun isModOwnedMenu(menu: AbstractContainerMenu): Boolean = impl?.isModOwnedMenu(menu) ?: false
    @JvmStatic fun isPersonalEnderChestMenu(menu: AbstractContainerMenu): Boolean =
        impl?.isPersonalEnderChestMenu(menu) ?: false

    @JvmStatic fun allowsContainerUse(player: ServerPlayer): Boolean = impl?.allowsContainerUse(player) ?: true

    @JvmStatic fun containerOpened(player: ServerPlayer) {
        impl?.containerOpened(player)
    }

    @JvmStatic fun containerClosed(player: ServerPlayer) {
        impl?.containerClosed(player)
    }

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

    @JvmStatic fun allowsCreatureBlockChange(level: Level, pos: BlockPos, creature: Entity?): Boolean =
        impl?.allowsCreatureBlockChange(level, pos, creature) ?: true

    @JvmStatic fun allowsExplosionDamage(level: Level, pos: BlockPos): Boolean =
        impl?.allowsExplosionDamage(level, pos) ?: true

    @JvmStatic fun allowsFireDamage(level: Level, pos: BlockPos): Boolean =
        impl?.allowsFireDamage(level, pos) ?: true

    @JvmStatic fun allowsPistonMove(
        level: Level,
        pistonPos: BlockPos,
        headPos: BlockPos?,
        toPush: List<BlockPos>,
        toDestroy: List<BlockPos>,
        pushDirection: Direction,
    ): Boolean = impl?.allowsPistonMove(level, pistonPos, headPos, toPush, toDestroy, pushDirection) ?: true
}
