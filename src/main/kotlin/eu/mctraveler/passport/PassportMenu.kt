package eu.mctraveler.passport

import com.mojang.authlib.GameProfile
import eu.mctraveler.MCTraveler
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.item.component.TooltipDisplay

object PassportMenu {
    enum class Page { MAIN, STAMPS }

    private const val ROWS = 3
    private const val STAMPS_SLOT = 22
    private const val BACK_SLOT = 26
    private const val PREVIOUS_SLOT = 24
    private const val NEXT_SLOT = 25
    private const val PAGE_SIZE = 26
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

    fun open(viewer: ServerPlayer, target: UUID) {
        if (viewer.containerMenu is PassportChestMenu) {
            viewer.sendSystemMessage(Paint.error("You already have a passport open"))
            return
        }
        val passport = MCTraveler.persistence?.passports?.get(target) ?: return
        val name = MCTraveler.persistence?.names?.usernameFor(target)
            ?: viewer.level().server.playerList.getPlayer(target)?.gameProfile?.name
            ?: target.toString()
        openPage(viewer, target, passport, name, Page.MAIN, 0)
    }

    private fun openPage(
        viewer: ServerPlayer,
        target: UUID,
        passport: Passport,
        name: String,
        page: Page,
        pageNumber: Int,
    ) {
        val contents = SimpleContainer(ROWS * 9)
        if (page == Page.MAIN) fillMain(contents, viewer, target, passport, name)
        else fillStamps(contents, passport, pageNumber)
        viewer.openMenu(
            SimpleMenuProvider(
                { id, inventory, _ ->
                    PassportChestMenu(id, inventory, contents, target, passport, name, page, pageNumber)
                },
                if (page == Page.MAIN) Component.literal("Passport of $name") else Component.literal("Passport stamps"),
            ),
        )
    }

    private fun fillMain(contents: Container, viewer: ServerPlayer, target: UUID, passport: Passport, name: String) {
        contents.setItem(4, head(viewer, target, name, passport))
        contents.setItem(10, item(Items.GRASS_BLOCK, "Biomes", "Biomes: ${passport.biomes.size}"))
        contents.setItem(11, item(Items.END_PORTAL_FRAME, "Dimensions", "Dimensions: ${passport.dimensions.size}"))
        val regionService = RegionsFeature.requireService()
        val regions = passport.regions.keys.count { regionService.byStableId(it) != null }
        val embassies = PassportJson.embassyCount(passport, regionService)
        contents.setItem(12, item(Items.OAK_SIGN, "Regions", "Regions: $regions ($embassies embassies)"))
        contents.setItem(13, item(Items.LEATHER_BOOTS, "Walked", "Walked: ${PassportFormatting.formatDistance(passport.distance.walk)}"))
        contents.setItem(14, item(Items.SADDLE, "Ridden", "Ridden: ${PassportFormatting.formatDistance(passport.distance.ride)}"))
        contents.setItem(15, item(Items.ELYTRA, "Flown", "Flown: ${PassportFormatting.formatDistance(passport.distance.fly)}"))
        contents.setItem(16, item(Items.WATER_BUCKET, "Swum", "Swum: ${PassportFormatting.formatDistance(passport.distance.swim)}"))
        contents.setItem(19, item(Items.SKELETON_SKULL, "Deaths", "Deaths: ${passport.deaths}"))
        contents.setItem(22, item(Items.NAME_TAG, "Stamps", "Stamps: ${passport.stamps.size}/${Stamps.ALL.size}"))
    }

    private fun fillStamps(contents: Container, passport: Passport, pageNumber: Int) {
        val start = pageNumber * PAGE_SIZE
        val page = Stamps.ALL.drop(start).take(PAGE_SIZE)
        page.forEachIndexed { index, stamp ->
            val at = passport.stamps[stamp.id]
            contents.setItem(index, stampItem(stamp, at))
        }
        contents.setItem(BACK_SLOT, button(Items.ARROW, "Back", "Return to passport"))
        if (Stamps.ALL.size > PAGE_SIZE) {
            if (pageNumber > 0) contents.setItem(PREVIOUS_SLOT, button(Items.SPECTRAL_ARROW, "Previous", "Previous page"))
            if (start + PAGE_SIZE < Stamps.ALL.size) contents.setItem(NEXT_SLOT, button(Items.SPECTRAL_ARROW, "Next", "Next page"))
        }
    }

    private fun head(viewer: ServerPlayer, target: UUID, name: String, passport: Passport): ItemStack {
        val profile = viewer.level().server.playerList.getPlayer(target)?.gameProfile ?: GameProfile(target, name)
        return ItemStack(Items.PLAYER_HEAD).apply {
            set(DataComponents.CUSTOM_NAME, nameComponent(name))
            set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
            set(DataComponents.LORE, lore(listOf("Traveler since ${dateFormat.format(Instant.ofEpochMilli(passport.firstJoin))}")))
            hideProfileTooltip(this)
        }
    }

    private fun stampItem(stamp: Stamp, unlockedAt: Long?): ItemStack {
        val unlocked = unlockedAt != null
        val icon = if (unlocked) {
            Items.PLAYER_HEAD
        } else {
            net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "gray_dye"),
            ).orElseThrow().value()
        }
        return ItemStack(icon).apply {
            set(DataComponents.CUSTOM_NAME, nameComponent("${stamp.icon} ${stamp.title}"))
            set(
                DataComponents.LORE,
                lore(
                    if (unlocked) listOf(Paint.green("Unlocked ${dateFormat.format(Instant.ofEpochMilli(unlockedAt!!))}"))
                    else listOf(Paint.red("Locked"), stamp.description),
                ),
            )
            hideProfileTooltip(this)
        }
    }

    private fun item(icon: net.minecraft.world.item.Item, title: String, description: String): ItemStack =
        ItemStack(icon).apply {
            set(DataComponents.CUSTOM_NAME, nameComponent(title))
            set(DataComponents.LORE, lore(listOf(description)))
        }

    private fun button(icon: net.minecraft.world.item.Item, title: String, description: String): ItemStack =
        item(icon, title, description)

    private fun nameComponent(text: String): Component =
        Component.literal(text).setStyle(Style.EMPTY.withItalic(false).withBold(true))

    private fun lore(lines: List<Any>): ItemLore =
        ItemLore(lines.flatMap { line ->
            when (line) {
                is Component -> listOf(line.copy().setStyle(line.style.withItalic(false)))
                else -> listOf(Component.literal(line.toString()).setStyle(Style.EMPTY.withItalic(false)))
            }
        })

    private fun hideProfileTooltip(stack: ItemStack) {
        stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.PROFILE, true))
    }

    class PassportChestMenu(
        containerId: Int,
        playerInventory: Inventory,
        private val contents: Container,
        private val target: UUID,
        private val passport: Passport,
        private val name: String,
        private val page: Page,
        private val pageNumber: Int,
    ) : ChestMenu(MenuType.GENERIC_9x3, containerId, playerInventory, contents, ROWS) {
        override fun clicked(slot: Int, button: Int, input: ContainerInput, player: Player) {
            if (player !is ServerPlayer || slot !in 0 until contents.containerSize) return
            player.level().server.execute {
                if (player.containerMenu !== this) return@execute
                when {
                    page == Page.MAIN && slot == STAMPS_SLOT -> {
                        player.closeContainer()
                        openPage(player, target, passport, name, Page.STAMPS, 0)
                    }
                    page == Page.STAMPS && slot == BACK_SLOT -> {
                        player.closeContainer()
                        openPage(player, target, passport, name, Page.MAIN, 0)
                    }
                    page == Page.STAMPS && slot == PREVIOUS_SLOT && pageNumber > 0 ->
                        run {
                            player.closeContainer()
                            openPage(player, target, passport, name, Page.STAMPS, pageNumber - 1)
                        }
                    page == Page.STAMPS && slot == NEXT_SLOT && (pageNumber + 1) * PAGE_SIZE < Stamps.ALL.size ->
                        run {
                            player.closeContainer()
                            openPage(player, target, passport, name, Page.STAMPS, pageNumber + 1)
                        }
                }
            }
        }

        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY
    }
}
