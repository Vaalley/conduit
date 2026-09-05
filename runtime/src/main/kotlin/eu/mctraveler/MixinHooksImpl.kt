package eu.mctraveler

import com.mojang.authlib.GameProfile
import eu.mctraveler.away.AwayFeature
import eu.mctraveler.bootstrap.MixinHooks
import eu.mctraveler.crystal.CrystalCrafting
import eu.mctraveler.crystal.CrystalDamageDisplay
import eu.mctraveler.crystal.CrystalRequests
import eu.mctraveler.embassy.EmbassyOrigins
import eu.mctraveler.identity.IdentityRemaps
import eu.mctraveler.importer.OrphanedSaveClaimFeature
import eu.mctraveler.motd.Motd
import eu.mctraveler.notepad.NotepadFeature
import eu.mctraveler.region.RegionEnvironment
import eu.mctraveler.region.RegionInteractables
import eu.mctraveler.region.RegionProtection
import eu.mctraveler.tablist.SpectatorVisibility
import eu.mctraveler.tablist.TabListFeature
import eu.mctraveler.weather.NoRain
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
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.CraftingContainer
import net.minecraft.world.inventory.ResultContainer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * What the bootstrap's permanent mixins call while this runtime is loaded — a
 * pure delegation table into the features (docs/hot-reload.md).
 */
object MixinHooksImpl : MixinHooks {
    override fun isAliased(uuid: UUID): Boolean = IdentityRemaps.isAliased(uuid)

    override fun remapProfile(profile: GameProfile): GameProfile = IdentityRemaps.remap(profile)

    override fun onPlayerCommand(player: ServerPlayer) = AwayFeature.onPlayerCommand(player)

    override fun isAway(player: ServerPlayer): Boolean = AwayFeature.isAway(player)

    override fun decorateStatus(status: ServerStatus, server: MinecraftServer): ServerStatus =
        Motd.decorate(status, server)

    override fun claimSaveBefore(nameAndId: NameAndId) = OrphanedSaveClaimFeature.claimBefore(nameAndId)

    override fun notepadHasSession(player: ServerPlayer): Boolean = NotepadFeature.hasSession(player)

    override fun notepadCompleteSession(player: ServerPlayer, pages: List<String>) =
        NotepadFeature.completeSession(player, pages)

    override fun notepadCancelSession(player: ServerPlayer) = NotepadFeature.cancelSession(player)

    override fun tabDisplayName(player: ServerPlayer): Component = TabListFeature.tabDisplayName(player)

    override fun maskSpectators(
        viewer: ServerPlayer,
        packet: ClientboundPlayerInfoUpdatePacket,
    ): ClientboundPlayerInfoUpdatePacket? = SpectatorVisibility.maskFor(viewer, packet)

    override fun maskHealthScore(
        viewer: ServerPlayer,
        packet: net.minecraft.network.protocol.game.ClientboundSetScorePacket,
    ): net.minecraft.network.protocol.game.ClientboundSetScorePacket? = SpectatorVisibility.maskScore(viewer, packet)

    override fun isVanishedFromViewer(target: ServerPlayer, viewer: ServerPlayer): Boolean =
        eu.mctraveler.vanish.VanishFeature.isHiddenFrom(target, viewer)

    override fun markdownLineFor(editor: ServerPlayer, raw: String): Component? =
        if (eu.mctraveler.rank.RankFeature.rankOf(editor) == eu.mctraveler.rank.Rank.DONATOR ||
            eu.mctraveler.region.RegionsFeature.isAdmin(editor)
        ) {
            eu.mctraveler.text.Markdown.parse(raw)
        } else {
            null
        }

    override fun beforeTeleport(player: ServerPlayer, destination: ResourceKey<Level>) =
        EmbassyOrigins.beforeTeleport(player, destination)

    override fun isCrystalAcceptCommand(command: String): Boolean = CrystalRequests.isAcceptCommand(command)

    override fun crystalAccept(acceptor: ServerPlayer, command: String) = CrystalRequests.accept(acceptor, command)

    override fun crystalCraftingGuard(
        menu: AbstractContainerMenu,
        player: Player,
        craftSlots: CraftingContainer,
        resultSlots: ResultContainer,
    ) = CrystalCrafting.guard(menu, player, craftSlots, resultSlots)

    override fun crystalDamageForViewer(viewer: ServerPlayer, packet: Packet<*>): Packet<*> =
        CrystalDamageDisplay.forViewer(viewer, packet)

    override fun weatherForViewer(viewer: ServerPlayer, packet: Packet<*>): Packet<*> =
        NoRain.forViewer(viewer, packet)

    override fun isModOwnedMenu(menu: AbstractContainerMenu): Boolean = RegionProtection.isModOwnedMenu(menu)
    override fun isPersonalEnderChestMenu(menu: AbstractContainerMenu): Boolean =
        RegionProtection.isPersonalEnderChestMenu(menu)
    override fun isWorkstationMenu(menu: AbstractContainerMenu): Boolean =
        RegionInteractables.isWorkstationMenu(menu)

    override fun allowsContainerUse(player: ServerPlayer): Boolean = RegionProtection.allowsContainerUse(player)

    override fun allowsVillagerTrade(player: ServerPlayer): Boolean = RegionProtection.allowsVillagerTrade(player)

    override fun containerOpened(player: ServerPlayer) = RegionProtection.containerOpened(player)

    override fun containerClosed(player: ServerPlayer) = RegionProtection.containerClosed(player)

    override fun containerTitleFor(
        player: ServerPlayer,
        menu: AbstractContainerMenu,
        default: Component,
    ): Component = RegionProtection.containerTitleFor(player, menu, default)

    override fun allowsPressurePlate(state: BlockState, level: Level, pos: BlockPos, entity: Entity): Boolean =
        RegionProtection.allowsPressurePlate(state, level, pos, entity)

    override fun allowsEntityDamage(entity: Entity, source: DamageSource): Boolean =
        RegionProtection.allowsEntityDamage(entity, source)

    override fun allowsBlockChange(player: ServerPlayer, level: Level, pos: BlockPos): Boolean =
        RegionProtection.allowsBlockChange(player, level, pos)

    override fun allowsProjectileBlockChange(projectile: Projectile, level: Level, pos: BlockPos): Boolean =
        RegionProtection.allowsProjectileBlockChange(projectile, level, pos)

    override fun allowsExplosionEntityEffect(level: Level, source: Entity?, target: Entity): Boolean =
        RegionEnvironment.allowsExplosionEntityEffect(level, source, target)

    override fun allowsPotionEffect(level: Level, thrower: Entity?, target: LivingEntity): Boolean =
        RegionEnvironment.allowsPotionEffect(level, thrower, target)

    override fun allowsCreatureBlockChange(level: Level, pos: BlockPos, creature: Entity?): Boolean =
        RegionEnvironment.allowsCreatureBlockChange(level, pos, creature)

    override fun allowsExplosionDamage(level: Level, pos: BlockPos): Boolean =
        RegionEnvironment.allowsExplosionDamage(level, pos)

    override fun allowsFireDamage(level: Level, pos: BlockPos): Boolean =
        RegionEnvironment.allowsFireDamage(level, pos)

    override fun allowsFluidSpread(level: Level, from: BlockPos, to: BlockPos): Boolean =
        RegionEnvironment.allowsFluidSpread(level, from, to)

    override fun allowsDecorationMove(level: Level, pos: BlockPos): Boolean =
        RegionEnvironment.allowsDecorationMove(level, pos)

    override fun allowsPistonMove(
        level: Level,
        pistonPos: BlockPos,
        headPos: BlockPos?,
        toPush: List<BlockPos>,
        toDestroy: List<BlockPos>,
        pushDirection: Direction,
    ): Boolean = RegionEnvironment.allowsPistonMove(level, pistonPos, headPos, toPush, toDestroy, pushDirection)
}
