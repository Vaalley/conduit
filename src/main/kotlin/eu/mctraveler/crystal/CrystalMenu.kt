package eu.mctraveler.crystal

import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.text.Paint
import eu.mctraveler.worlds.DimensionRole
import eu.mctraveler.worlds.WorldsFeature
import java.util.UUID
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import net.minecraft.world.level.portal.TeleportTransition

/**
 * The crystal's destination menu (spec User Stories 26-34; Nucleus
 * `createTeleportationCrystalInventory`, `createPlayersInventory`,
 * `useTeleportationCrystal` and `TeleportationCrystalListener.onInventoryClick`).
 *
 * Right-clicking a crystal opens a chest GUI of five destinations; picking one
 * closes the menu, moves the player, and spends a point of [CrystalEnergy].
 * Picking *Player* opens a second GUI of everyone else online, and clicking a
 * head sends them a teleport request ([CrystalRequests]).
 *
 * Nothing in either GUI is an item a player can have. Both are [CrystalChestMenu]s
 * over a throwaway [SimpleContainer], and that menu swallows every click rather
 * than letting vanilla move anything — which is also the marker region
 * protection uses to tell a mod-owned menu from a chest (spec deviation 16).
 *
 * **State is not tracked.** Nucleus kept two `WeakHashMap`s of who had which GUI
 * open and swept them on close and quit; here the open menu *is* the state
 * ([openMenuOf]), so there is nothing to leak and nothing to sweep — story 36
 * falls out of the container lifecycle vanilla already runs.
 */
object CrystalMenu {

    /** The destination GUI's title, exactly as Nucleus titled it. */
    const val TITLE = "Where would you like to go?"

    /** The head GUI's title. */
    const val PLAYERS_TITLE = "Select a player"

    /** The slot Nucleus's first destination sits in. */
    const val FIRST_ACTION_SLOT = 11

    /**
     * The last slot a click is *accepted* in. There are only five destinations,
     * so slot 16 — a blue pane — is accepted and then maps to nothing. Nucleus's
     * window was one wider than its action list and this reproduces that
     * exactly; the visible behaviour is that clicking that pane does nothing,
     * which is also what the panes either side of it do.
     */
    const val LAST_ACTION_SLOT = 16

    /** Which of the two GUIs a [CrystalChestMenu] is. */
    enum class Kind { DESTINATIONS, PLAYERS }

    /** One online player, as the head GUI offers them. */
    data class Head(val uuid: UUID, val name: String)

    /** Where a destination puts the player. */
    private class Landing(
        val level: ServerLevel,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
    )

    /**
     * One of the five buttons: how it looks, and where it leads. A null landing
     * means the click is over — either the destination refused (and has said so)
     * or it opened the head GUI instead — and in that case no energy is spent.
     */
    private class Destination(
        val name: String,
        val icon: Item,
        val lore: List<String>,
        val resolve: (ServerPlayer) -> Landing?,
    )

    /**
     * Nucleus's five destinations in its own order; the slot each occupies is
     * [FIRST_ACTION_SLOT] plus its index here.
     */
    private val DESTINATIONS: List<Destination> = listOf(
        Destination(
            name = "Bed",
            // Dyed variants live in a ColorCollection since 26.2 rather than as
            // one constant per colour.
            icon = Items.BED.blue(),
            lore = listOf("Go back to your place of rest"),
            resolve = ::bed,
        ),
        Destination(
            name = "Spawn",
            icon = Items.SPAWNER,
            lore = listOf("Head to spawn town"),
            resolve = ::spawn,
        ),
        Destination(
            name = "Player",
            icon = Items.PLAYER_HEAD,
            lore = listOf("Request to teleport to a player", "costs even if they don't accept"),
            resolve = ::players,
        ),
        Destination(
            name = "Embassy",
            icon = Items.SPYGLASS,
            lore = listOf("Teleport to the embassy world"),
            resolve = ::embassy,
        ),
        Destination(
            name = "Wilderness",
            icon = Items.GRASS_BLOCK,
            lore = listOf("Coming soon"),
            resolve = ::wilderness,
        ),
    )

    // ---- opening ----

    /**
     * Right-click with a crystal of [tier] (spec story 27). Refuses when a
     * crystal menu is already open, or when the player has too little energy
     * for this tier — a tier-1 crystal needs a full pool, a tier-3 works down
     * to one point. Neither refusal costs anything.
     */
    fun use(player: ServerPlayer, tier: Int) {
        if (openMenuOf(player) != null) {
            player.sendSystemMessage(Paint.error("You are already in a teleportation crystal."))
            return
        }
        if (CrystalEnergy.energyOf(player) <= CrystalEnergy.MAX_ENERGY - tier) {
            player.sendSystemMessage(Paint.error("You have no energy, please wait for a recharge"))
            return
        }
        openDestinations(player)
    }

    /** Opens the destination GUI (spec story 26). */
    fun openDestinations(player: ServerPlayer) {
        val contents = SimpleContainer(27)
        val black = Items.STAINED_GLASS_PANE.black()
        val blue = Items.STAINED_GLASS_PANE.blue()
        for (column in 0 until 9) {
            contents.setItem(column, pane(black))
            contents.setItem(column + 18, pane(black))
            val destination = DESTINATIONS.getOrNull(column - 2).takeIf { column in 2..6 }
            contents.setItem(column + 9, destination?.let(::button) ?: pane(blue))
        }
        open(player, Kind.DESTINATIONS, contents, rows = 3, title = TITLE, heads = emptyList())
    }

    /** The tallest chest screen the protocol has: six rows of nine. */
    const val MAX_ROWS = 6

    /**
     * Opens the head GUI over everyone else online (spec story 33), one row per
     * nine of them.
     *
     * Capped at [MAX_ROWS]. Nucleus's row count was unbounded, which on a server
     * with more than 54 other players online would have asked Bukkit for an
     * inventory larger than any chest screen; here it would have meant a menu
     * whose slot count disagreed with the screen type sent to the client. The
     * cap is a real limit on who can be picked, so it takes the first
     * [MAX_ROWS] * 9 rather than silently building a broken screen.
     */
    fun openPlayers(player: ServerPlayer, others: List<ServerPlayer>) {
        val shown = others.take(MAX_ROWS * 9)
        val rows = rowsFor(shown.size)
        val contents = SimpleContainer(rows * 9)
        for ((slot, other) in shown.withIndex()) contents.setItem(slot, head(other))
        val heads = shown.map { Head(it.uuid, it.gameProfile.name) }
        open(player, Kind.PLAYERS, contents, rows, PLAYERS_TITLE, heads)
    }

    private fun open(
        player: ServerPlayer,
        kind: Kind,
        contents: Container,
        rows: Int,
        title: String,
        heads: List<Head>,
    ) {
        player.openMenu(
            SimpleMenuProvider(
                { containerId, inventory, _ ->
                    CrystalChestMenu(containerId, inventory, contents, rows, kind, heads)
                },
                Component.literal(title),
            ),
        )
    }

    /**
     * How many rows of nine it takes to show [others] heads — Nucleus's
     * `ceil(size / 9.0)`, never less than one (an empty menu is unreachable but
     * a zero-row screen is not a thing) and never more than [MAX_ROWS].
     */
    fun rowsFor(others: Int): Int =
        ((others.coerceAtMost(MAX_ROWS * 9) + 8) / 9).coerceIn(1, MAX_ROWS)

    /** The crystal GUI [player] has open, if any — the whole of story 36's state. */
    fun openMenuOf(player: ServerPlayer): CrystalChestMenu? =
        player.containerMenu as? CrystalChestMenu

    /**
     * Closes any crystal GUI still open across the server (spec story 36's
     * server-stop clause). Nucleus closed its two tracking maps' keys; the open
     * menus themselves are the same set.
     */
    fun closeAll(server: net.minecraft.server.MinecraftServer) {
        for (player in server.playerList.players.toList()) {
            if (openMenuOf(player) != null) player.closeContainer()
        }
    }

    // ---- clicking ----

    /**
     * A destination was picked (spec story 28). The menu closes first, then the
     * destination decides; only a destination that actually moved the player
     * costs energy and reports it.
     */
    private fun choose(player: ServerPlayer, destination: Destination) {
        player.closeContainer()
        val landing = destination.resolve(player) ?: return
        player.teleportTo(
            landing.level,
            landing.x,
            landing.y,
            landing.z,
            emptySet(),
            landing.yaw,
            landing.pitch,
            false,
        )
        CrystalEnergy.modify(player, -1)
        player.sendSystemMessage(
            Paint.info("You used one energy going to ", Paint.aqua(destination.name.lowercase())),
        )
    }

    /** A head was clicked (spec story 34). */
    private fun requestTeleport(player: ServerPlayer, head: Head) {
        player.closeContainer()
        CrystalRequests.send(player, head)
    }

    // ---- the five destinations ----

    /**
     * The player's own respawn point (spec story 29), as vanilla resolves it —
     * which routes through the Worlds respawn plumbing
     * ([eu.mctraveler.mixin.ServerPlayerRespawnMixin]), so a bed in another
     * World behaves here exactly as it does on death.
     *
     * Resolved without consuming the spawn block: this is travel, not a
     * respawn, so an exhausted respawn anchor must not be spent. A player with
     * no respawn point — or one whose bed has since been broken, which is what
     * `missingRespawnBlock` reports — is refused, and refused for free.
     */
    private fun bed(player: ServerPlayer): Landing? {
        val transition = player.respawnConfig
            ?.let { player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING) }
            ?.takeUnless { it.missingRespawnBlock() }
        if (transition == null) {
            player.sendSystemMessage(Paint.error("You have no bed to go to"))
            return null
        }
        return Landing(
            transition.newLevel(),
            transition.position().x,
            transition.position().y,
            transition.position().z,
            transition.yRot(),
            transition.xRot(),
        )
    }

    /** Spawn town, in Primary's overworld (spec story 30) — Nucleus's `kSpawnLocation`. */
    private fun spawn(player: ServerPlayer): Landing? {
        val server = player.level().server
        val primary = WorldsFeature.worlds?.byId("primary")?.dimension(DimensionRole.OVERWORLD)
            ?: Level.OVERWORLD
        val level = server.getLevel(primary) ?: server.overworld()
        return Landing(level, 16.5, 71.0, -15.5, 180.0f, 0.0f)
    }

    /**
     * Not a place but a second menu (spec story 33): the head GUI, unless the
     * player is the only one online. Either way this destination never lands
     * anywhere, so the energy is spent by the *head* click, not by this one.
     */
    private fun players(player: ServerPlayer): Landing? {
        val others = player.level().server.playerList.players.filter { it != player }
        if (others.isEmpty()) {
            player.sendSystemMessage(Paint.error("No-one else is online"))
            return null
        }
        openPlayers(player, others)
        return null
    }

    /**
     * The embassies dimension (spec story 31). The origin the player is leaving
     * is recorded by the teleport itself
     * ([eu.mctraveler.mixin.EmbassyOriginMixin]), so there is nothing to do here
     * but arrive.
     */
    private fun embassy(player: ServerPlayer): Landing? {
        val level = player.level().server.getLevel(EmbassiesFeature.DIMENSION)
        if (level == null) {
            player.sendSystemMessage(Paint.error("The embassy world is not available"))
            return null
        }
        return Landing(level, 0.5, 1.0, 0.5, player.yRot, player.xRot)
    }

    /** The stub that is the feature (spec story 32, and Out of Scope). */
    private fun wilderness(player: ServerPlayer): Landing? {
        player.sendSystemMessage(Paint.error("Sorry, this feature is not available yet"))
        return null
    }

    // ---- the items ----

    private fun pane(item: Item): ItemStack = ItemStack(item)

    /**
     * A destination button. Nucleus set Bukkit's *display name*, which is
     * `custom_name` — the component vanilla renders in italic, the way an
     * anvil-renamed item reads. `item_name` would render upright and would not
     * match what players saw, so the crystal's own name (`item_name`, as
     * Nucleus set it) and these buttons deliberately use different components.
     */
    private fun button(destination: Destination): ItemStack =
        ItemStack(destination.icon).apply {
            set(DataComponents.CUSTOM_NAME, Component.literal(destination.name))
            set(DataComponents.LORE, loreOf(destination.lore))
            hideAdditionalTooltip(this)
        }

    /** One online player's head, wearing their own skin (spec story 33). */
    private fun head(other: ServerPlayer): ItemStack =
        ItemStack(Items.PLAYER_HEAD).apply {
            set(DataComponents.CUSTOM_NAME, Component.literal(other.gameProfile.name))
            set(DataComponents.PROFILE, ResolvableProfile.createResolved(other.gameProfile))
            set(DataComponents.LORE, loreOf(listOf("Click to teleport to this player")))
            hideAdditionalTooltip(this)
        }

    private fun loreOf(lines: List<String>): net.minecraft.world.item.component.ItemLore =
        net.minecraft.world.item.component.ItemLore(lines.map(Component::literal))

    /**
     * Nucleus's `HIDE_ADDITIONAL_TOOLTIP`, in the form 26.2 has for it: the flag
     * became per-component, so what used to be one blanket hide is now naming
     * the component that would have drawn the extra line.
     *
     * Of these five icons plus the heads, [DataComponents.PROFILE] is the only
     * one that draws anything — the head's owner name, which would repeat the
     * name already on the item. The others (bed, spawner, spyglass, grass) carry
     * no additional tooltip to hide, so this is a no-op on them and is applied
     * uniformly rather than special-cased.
     */
    private fun hideAdditionalTooltip(stack: ItemStack) {
        stack.set(
            DataComponents.TOOLTIP_DISPLAY,
            TooltipDisplay.DEFAULT.withHidden(DataComponents.PROFILE, true),
        )
    }

    /**
     * A crystal GUI. Extends [ChestMenu] purely so the two things that make it
     * ours are in one place: every click is swallowed, and the type itself is
     * the "mod-owned menu" marker region protection looks for (spec deviation 16).
     */
    class CrystalChestMenu(
        containerId: Int,
        playerInventory: Inventory,
        private val contents: Container,
        rows: Int,
        val kind: Kind,
        val heads: List<Head>,
    ) : ChestMenu(menuTypeFor(rows), containerId, playerInventory, contents, rows) {

        /**
         * Every click, swallowed. Never calls super, so pick-up, shift-click
         * (quick move), hotbar swap, throw, clone and drag are all covered by
         * this one line rather than by enumerating them — the player can no more
         * take a glass pane than a head. Vanilla reconciles the client's
         * optimistic prediction against the untouched server state immediately
         * after this returns, so a swallowed click simply snaps back.
         */
        override fun clicked(slot: Int, button: Int, input: ContainerInput, player: Player) {
            if (player !is ServerPlayer) return
            // Below the divider is the player's own inventory, and -999 is the
            // click that lands outside the window entirely.
            if (slot < 0 || slot >= contents.containerSize) return
            val action = actionFor(slot) ?: return
            // The action closes this menu and may open the next one. It cannot
            // run inside the click: vanilla re-reads `player.containerMenu` for
            // its post-click bookkeeping, and would apply it to whatever we had
            // left open. Queued on the server thread, it lands after
            // handleContainerClick has finished with this menu.
            player.level().server.execute { action(player) }
        }

        /**
         * Unreachable while [clicked] never delegates, and empty so it stays
         * that way if some other path ever asks: nothing in a crystal GUI moves.
         */
        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

        private fun actionFor(slot: Int): ((ServerPlayer) -> Unit)? = when (kind) {
            Kind.DESTINATIONS ->
                if (slot !in FIRST_ACTION_SLOT..LAST_ACTION_SLOT) {
                    null
                } else {
                    DESTINATIONS.getOrNull(slot - FIRST_ACTION_SLOT)
                        ?.let { destination -> { player -> choose(player, destination) } }
                }

            Kind.PLAYERS ->
                heads.getOrNull(slot)?.let { head -> { player -> requestTeleport(player, head) } }
        }

        private companion object {
            fun menuTypeFor(rows: Int): MenuType<ChestMenu> = when (rows) {
                1 -> MenuType.GENERIC_9x1
                2 -> MenuType.GENERIC_9x2
                3 -> MenuType.GENERIC_9x3
                4 -> MenuType.GENERIC_9x4
                5 -> MenuType.GENERIC_9x5
                else -> MenuType.GENERIC_9x6
            }
        }
    }
}
