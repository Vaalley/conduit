package eu.mctraveler.region

import eu.mctraveler.text.Paint
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
import net.minecraft.world.item.component.TooltipDisplay

/**
 * The region-flags GUI (`/rg flags`), replacing the old chat-based `/rg flag`
 * toggle: a 27-slot single-chest [MAIN] page any resident or admin may open
 * and toggle, and a 27-slot [ADMIN] sub-page (the 5 admin-only flags, plus
 * the read-only `EMBASSY` flag) reached by a command-block icon in the
 * bottom-right corner of the main page, for an admin only.
 *
 * Modeled directly on [eu.mctraveler.crystal.CrystalMenu] — the only existing
 * custom-menu pattern in this codebase: every click is swallowed
 * ([RegionFlagsChestMenu.clicked] never calls `super.clicked`), the actual
 * mutation is deferred one tick via `player.level().server.execute { ... }`
 * guarded by `player.containerMenu === this`, and [quickMoveStack] returns
 * [ItemStack.EMPTY] — nothing in any slot is a real item a client can ever
 * extract.
 *
 * **Security.** Chest GUIs are a known exploit surface (see the plan this
 * implements), so beyond what [eu.mctraveler.crystal.CrystalMenu] already
 * gives every mod-owned menu for free:
 *
 * 1. [openMain]/[openAdmin] refuse to open a second instance over a menu
 *    already open ([alreadyOpen]), mirroring
 *    [eu.mctraveler.crystal.CrystalMenu.use]'s existing guard.
 * 2. Every toggle re-checks authorization (`isResident`/`isAdmin`) at click
 *    time against the *live* player state, not just at open time — a client
 *    faking a close locally, without ever sending the close packet, cannot
 *    keep a menu open server-side across a membership change and use it to
 *    toggle flags it no longer has standing for. (The deferred-mutation
 *    guard above already refuses once `containerMenu` has genuinely moved on;
 *    this is the belt to that braces — the *server's* own authorization
 *    state, not just its idea of which menu is open, is what is asked again.)
 * 3. The region is re-resolved by identity ([RegionService.contains]) rather
 *    than trusted from the object captured at open time: a region deleted
 *    while its flags GUI is open fails the toggle safely instead of mutating
 *    a detached `Region`.
 */
object RegionFlagsMenu {

    /** Which of the two 27-slot pages a [RegionFlagsChestMenu] is showing. */
    enum class Page { MAIN, ADMIN }

    private const val ROWS = 3
    private const val ADMIN_BUTTON_SLOT = 26
    private const val BACK_BUTTON_SLOT = 26

    /** Row-grouped slot layout for [RegionFlags.MAIN]'s 16 items (6 + 5 + 5). */
    private fun mainSlotFor(index: Int): Int = when {
        index < 6 -> index
        index < 11 -> 9 + (index - 6)
        else -> 18 + (index - 11)
    }

    private fun adminSlotFor(index: Int): Int = index

    // ---- opening ----

    /**
     * Opens the main page for [player] over [region] — requires residency or
     * admin, same shape as `rename`/`extend`: main-page flags are
     * self-service now, not admin-gated.
     */
    fun openMain(player: ServerPlayer, region: Region) {
        if (alreadyOpen(player)) return
        if (!region.isResident(player.uuid) && !RegionsFeature.isAdmin(player)) {
            player.sendSystemMessage(Paint.error("You are not a member of this region"))
            return
        }
        open(player, Page.MAIN, region)
    }

    /**
     * Opens the admin page for [player] over [region] — requires admin. The
     * admin button is never placed into a non-admin's main-page item list, so
     * reaching this without being an admin means a forged or replayed
     * interaction rather than a normal click; this gate is the backstop, not
     * the primary control.
     */
    fun openAdmin(player: ServerPlayer, region: Region) {
        if (alreadyOpen(player)) return
        RegionsFeature.adminGate(player)?.let {
            player.sendSystemMessage(it)
            return
        }
        open(player, Page.ADMIN, region)
    }

    /** Refuses a second menu instance over one already open (security safeguard 1). */
    private fun alreadyOpen(player: ServerPlayer): Boolean {
        if (player.containerMenu is RegionFlagsChestMenu) {
            player.sendSystemMessage(Paint.error("You already have region flags open"))
            return true
        }
        return false
    }

    private fun open(player: ServerPlayer, page: Page, region: Region) {
        val isAdminViewer = RegionsFeature.isAdmin(player)
        val contents = SimpleContainer(ROWS * 9)
        fill(contents, page, region, isAdminViewer)
        player.openMenu(
            SimpleMenuProvider(
                { containerId, inventory, _ ->
                    RegionFlagsChestMenu(containerId, inventory, contents, page, region, isAdminViewer)
                },
                Paint.green.bold("Region flag settings"),
            ),
        )
    }

    /** The GUI [player] has open, if any. */
    fun openMenuOf(player: ServerPlayer): RegionFlagsChestMenu? =
        player.containerMenu as? RegionFlagsChestMenu

    // ---- drawing ----

    private fun definitionsFor(page: Page): List<RegionFlags.Definition> = when (page) {
        Page.MAIN -> RegionFlags.MAIN
        Page.ADMIN -> RegionFlags.ADMIN
    }

    private fun slotFor(page: Page, index: Int): Int = when (page) {
        Page.MAIN -> mainSlotFor(index)
        Page.ADMIN -> adminSlotFor(index)
    }

    private fun fill(contents: Container, page: Page, region: Region, isAdminViewer: Boolean) {
        for (slot in 0 until contents.containerSize) contents.setItem(slot, ItemStack.EMPTY)
        val defs = definitionsFor(page)
        for ((index, def) in defs.withIndex()) {
            contents.setItem(slotFor(page, index), flagItem(region, def))
        }
        when (page) {
            Page.MAIN -> contents.setItem(ADMIN_BUTTON_SLOT, if (isAdminViewer) adminButtonItem() else fillerItem())
            Page.ADMIN -> contents.setItem(BACK_BUTTON_SLOT, backButtonItem())
        }
    }

    private fun flagItem(region: Region, def: RegionFlags.Definition): ItemStack {
        val allowed = def.id in region.flags
        val status = when (def.statusStyle) {
            RegionFlags.StatusStyle.ALLOWED_DISALLOWED -> if (allowed) Paint.green("Allowed") else Paint.red("Disallowed")
            RegionFlags.StatusStyle.TRUE_FALSE -> if (allowed) Paint.green("True") else Paint.red("False")
        }
        return ItemStack(def.icon).apply {
            set(DataComponents.CUSTOM_NAME, upright(def.label))
            set(DataComponents.LORE, loreOf(listOf<Any>(status, Component.empty()) + def.description))
            hideAdditionalTooltip(this)
        }
    }

    private fun adminButtonItem(): ItemStack =
        ItemStack(Items.REPEATING_COMMAND_BLOCK).apply {
            set(DataComponents.CUSTOM_NAME, upright("Admin flags"))
            set(DataComponents.LORE, loreOf(listOf("Click to view admin-only flags")))
            hideAdditionalTooltip(this)
        }

    private fun backButtonItem(): ItemStack =
        ItemStack(Items.ARROW).apply {
            set(DataComponents.CUSTOM_NAME, upright("Back"))
            set(DataComponents.LORE, loreOf(listOf("Click to return to region flags")))
            hideAdditionalTooltip(this)
        }

    private fun fillerItem(): ItemStack =
        ItemStack(Items.STAINED_GLASS_PANE.gray()).apply {
            set(DataComponents.CUSTOM_NAME, upright(" "))
            hideAdditionalTooltip(this)
        }

    /**
     * Vanilla renders `custom_name` and lore lines in italic by default; every
     * name, status word and lore line in this GUI is meant to read upright, so
     * the italic bit is explicitly cleared on the component's own style.
     */
    private fun upright(text: String): Component =
        Component.literal(text).setStyle(Style.EMPTY.withItalic(false))

    private fun upright(component: Component): Component {
        val copy = component.copy()
        return copy.setStyle(copy.style.withItalic(false))
    }

    private fun loreOf(lines: List<Any>): ItemLore =
        ItemLore(lines.map { upright(if (it is Component) it else Component.literal(it.toString())) })

    private fun hideAdditionalTooltip(stack: ItemStack) {
        stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.PROFILE, true))
    }

    // ---- clicking ----

    /**
     * Flips [def]'s presence in [region]'s flags, re-checking both the
     * region's continued existence and the player's live authorization first
     * (security safeguards 2 and 3) — a stale menu kept open across a
     * membership change, or a region deleted mid-session, refuses cleanly
     * rather than mutating anything.
     */
    private fun toggle(
        player: ServerPlayer,
        region: Region,
        def: RegionFlags.Definition,
        contents: Container,
        slot: Int,
        page: Page,
    ) {
        if (!RegionsFeature.requireService().contains(region)) return
        val authorized = when (page) {
            Page.MAIN -> region.isResident(player.uuid) || RegionsFeature.isAdmin(player)
            Page.ADMIN -> RegionsFeature.isAdmin(player)
        }
        if (!authorized) return
        if (!def.toggleable) {
            player.sendSystemMessage(Paint.error("You cannot toggle the embassy flag"))
            return
        }
        val added = region.flags.add(def.id)
        if (!added) region.flags.remove(def.id)
        RegionsFeature.requireService().save()
        // SCOREBOARD decides whether the sidebar is drawn at all, so a toggle
        // takes effect on the occupants now, not next time they walk in.
        if (def.id == "SCOREBOARD") RegionTracker.redraw(player.level().server, region)
        contents.setItem(slot, flagItem(region, def))
    }

    private fun openAdminFromMain(player: ServerPlayer, region: Region) {
        if (!RegionsFeature.requireService().contains(region)) return
        player.closeContainer()
        openAdmin(player, region)
    }

    private fun openMainFromAdmin(player: ServerPlayer, region: Region) {
        if (!RegionsFeature.requireService().contains(region)) return
        player.closeContainer()
        openMain(player, region)
    }

    /**
     * A region-flags GUI. Extends [ChestMenu] purely so the two things that
     * make it ours are in one place: every click is swallowed, and the type
     * itself is the "mod-owned menu" marker region protection looks for.
     */
    class RegionFlagsChestMenu(
        containerId: Int,
        playerInventory: Inventory,
        private val contents: Container,
        val page: Page,
        private val region: Region,
        private val isAdminViewer: Boolean,
    ) : ChestMenu(MenuType.GENERIC_9x3, containerId, playerInventory, contents, ROWS) {

        /**
         * Every click, swallowed — never delegates to `super`, so nothing in
         * this menu can ever be picked up, shift-clicked, hotbar-swapped,
         * thrown, cloned or dragged. The action itself is queued for the next
         * server tick, after vanilla has finished its own post-click
         * bookkeeping for this menu, and only runs if the player is still
         * looking at this exact menu instance by then.
         */
        override fun clicked(slot: Int, button: Int, input: ContainerInput, player: Player) {
            if (player !is ServerPlayer) return
            if (slot < 0 || slot >= contents.containerSize) return
            val action = actionFor(slot) ?: return
            player.level().server.execute {
                if (player.containerMenu === this) action(player)
            }
        }

        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

        private fun actionFor(slot: Int): ((ServerPlayer) -> Unit)? {
            val defs = definitionsFor(page)
            val index = (0 until defs.size).firstOrNull { slotFor(page, it) == slot }
            if (index != null) {
                val def = defs[index]
                return { player -> toggle(player, region, def, contents, slot, page) }
            }
            return when (page) {
                Page.MAIN -> if (slot == ADMIN_BUTTON_SLOT) {
                    { player -> openAdminFromMain(player, region) }
                } else {
                    null
                }
                Page.ADMIN -> if (slot == BACK_BUTTON_SLOT) {
                    { player -> openMainFromAdmin(player, region) }
                } else {
                    null
                }
            }
        }
    }
}
