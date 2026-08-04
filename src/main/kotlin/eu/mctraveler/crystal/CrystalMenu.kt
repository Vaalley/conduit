package eu.mctraveler.crystal

import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.text.Paint
import eu.mctraveler.worlds.Landing
import java.util.UUID
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
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
import net.minecraft.world.level.portal.TeleportTransition

/**
 * The crystal's destination menu (spec User Stories 26-34; Nucleus
 * `createTeleportationCrystalInventory`, `createPlayersInventory`,
 * `useTeleportationCrystal` and `TeleportationCrystalListener.onInventoryClick`).
 *
 * Right-clicking a crystal opens a chest GUI of configured destinations; picking one
 * closes the menu, moves the player, and spends a point of [CrystalEnergy]
 * unless it is one of the two free spawns.
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

    /** The slot the first destination sits in. */
    const val FIRST_ACTION_SLOT = 11

    /** The last action slot on the default three-row destination screen. */
    const val LAST_ACTION_SLOT = 16

    /** Which of the two GUIs a [CrystalChestMenu] is. */
    enum class Kind { DESTINATIONS, PLAYERS }

    /** One online player, as the head GUI offers them. */
    data class Head(val uuid: UUID, val name: String)

    /**
     * One destination button: how it looks, whether it is free, and where it leads. A null landing
     * means the click is over — either the destination refused (and has said so)
     * or it opened the head GUI instead — and in that case no energy is spent.
     */
    private class Destination(
        val name: String,
        val icon: Item,
        val lore: List<String>,
        val free: Boolean = false,
        val resolve: (ServerPlayer, Int) -> Landing?,
    )

    /**
     * The fixed destinations and configured spawns in their menu order; the slot each occupies is
     * [FIRST_ACTION_SLOT] plus its index here.
     */
    private fun destinations(): List<Destination> {
        return buildList {
            add(
                Destination(
                    name = "Bed",
                    // Dyed variants live in a ColorCollection since 26.2 rather than as
                    // one constant per colour.
                    icon = Items.BED.blue(),
                    lore = listOf("Go back to your place of rest"),
                    resolve = { player, _ -> bed(player) },
                ),
            )
            addAll(
                CrystalSpawns.definitions().mapIndexed { index, spawn ->
                    Destination(
                        name = title(spawn.name),
                        icon = Items.SPAWNER,
                        lore = if (index == 0) {
                            listOf("Head to spawn town")
                        } else if (index == 1 && spawn.name == "spawn 2") {
                            listOf("Head to the remote spawn")
                        } else {
                            listOf("Head to ${spawn.name}")
                        },
                        free = true,
                        resolve = { player, _ -> CrystalSpawns.landing(player, spawn) },
                    )
                },
            )
            add(
                Destination(
                    name = "Player",
                    icon = Items.PLAYER_HEAD,
                    lore = listOf("Request to teleport to a player", "costs one energy when they accept"),
                    resolve = ::players,
                ),
            )
            add(
                Destination(
                    name = "Embassy",
                    icon = Items.SPYGLASS,
                    lore = listOf("Teleport to the embassy world"),
                    resolve = { player, _ -> embassy(player) },
                ),
            )
            add(
                Destination(
                    name = "Wilderness",
                    icon = Items.GRASS_BLOCK,
                    lore = listOf("Coming soon"),
                    resolve = { player, _ -> wilderness(player) },
                ),
            )
        }
    }

    private fun visibleDestinations(): List<Destination> =
        destinations().take((MAX_ROWS - 2) * ACTIONS_PER_ROW)

    // ---- opening ----

    /**
     * Right-click with a crystal of [tier] (spec story 27). Refuses only when
     * a crystal menu is already open. Paid destinations apply the tier's
     * charge requirement when clicked; free spawns are always available.
     */
    fun use(player: ServerPlayer, tier: Int) {
        if (openMenuOf(player) != null) {
            player.sendSystemMessage(Paint.error("You are already in a teleportation crystal."))
            return
        }
        openDestinations(player, CrystalItem.chargesOf(tier))
    }

    /** Opens the destination GUI (spec story 26). */
    fun openDestinations(player: ServerPlayer, crystalCharges: Int) {
        val destinations = visibleDestinations()
        val rows = destinationRows(destinations.size)
        val contents = SimpleContainer(rows * 9)
        val black = Items.STAINED_GLASS_PANE.black()
        val blue = Items.STAINED_GLASS_PANE.blue()
        for (slot in 0 until contents.containerSize) {
            val row = slot / 9
            contents.setItem(slot, pane(if (row == 0 || row == rows - 1) black else blue))
        }
        contents.setItem(4, energyInfo(player))
        for ((slot, destination) in actionSlots(destinations.size).zip(destinations)) {
            contents.setItem(slot, button(destination))
        }
        open(player, Kind.DESTINATIONS, contents, rows, TITLE, emptyList(), crystalCharges)
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
    fun openPlayers(player: ServerPlayer, others: List<ServerPlayer>, crystalCharges: Int) {
        val shown = others.take(MAX_ROWS * 9)
        val rows = rowsFor(shown.size)
        val contents = SimpleContainer(rows * 9)
        for ((slot, other) in shown.withIndex()) contents.setItem(slot, head(other))
        val heads = shown.map { Head(it.uuid, it.gameProfile.name) }
        open(player, Kind.PLAYERS, contents, rows, PLAYERS_TITLE, heads, crystalCharges)
    }

    private fun open(
        player: ServerPlayer,
        kind: Kind,
        contents: Container,
        rows: Int,
        title: String,
        heads: List<Head>,
        crystalCharges: Int,
    ) {
        player.openMenu(
            SimpleMenuProvider(
                { containerId, inventory, _ ->
                    CrystalChestMenu(containerId, inventory, contents, rows, kind, heads, crystalCharges)
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
     * A destination was picked (spec story 28). Paid destinations first check
     * the opening crystal's tier requirement; only an allowed destination then
     * closes the menu and decides whether to spend energy.
     */
    private fun choose(player: ServerPlayer, destination: Destination, crystalCharges: Int) {
        if (!destination.free &&
            CrystalEnergy.energyOf(player) <= CrystalEnergy.MAX_ENERGY - crystalCharges
        ) {
            player.sendSystemMessage(Paint.error("You have no energy, please wait for a recharge"))
            return
        }
        player.closeContainer()
        val landing = destination.resolve(player, crystalCharges) ?: return
        landing.send(player)
        if (destination.free) {
            player.sendSystemMessage(Paint.info("You arrived at ", Paint.aqua(destination.name.lowercase())))
        } else {
            CrystalEnergy.modify(player, -1)
            player.sendSystemMessage(
                Paint.info("You used one energy going to ", Paint.aqua(destination.name.lowercase())),
            )
        }
    }

    /** A head was clicked (spec story 34). */
    private fun requestTeleport(player: ServerPlayer, head: Head) {
        player.closeContainer()
        CrystalRequests.send(player, head)
    }

    // ---- the destinations ----

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

    /**
     * Not a place but a second menu (spec story 33): the head GUI, unless the
     * player is the only one online. Either way this destination never lands
     * anywhere, so the energy is spent by the *head* click, not by this one.
     */
    private fun players(player: ServerPlayer, crystalCharges: Int): Landing? {
        val others = player.level().server.playerList.players.filter { it != player }
        if (others.isEmpty()) {
            player.sendSystemMessage(Paint.error("No-one else is online"))
            return null
        }
        openPlayers(player, others, crystalCharges)
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
        // Facing zeroed, not carried over. Nucleus built its destination as
        // `Location(world, x, y, z)`, whose yaw and pitch default to 0, so every
        // arrival in the embassies dimension faced due south and level.
        return Landing(level, 0.5, 1.0, 0.5, 0.0f, 0.0f)
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

    private fun energyInfo(player: ServerPlayer): ItemStack =
        ItemStack(Items.AMETHYST_SHARD).apply {
            set(
                DataComponents.CUSTOM_NAME,
                Component.literal("Energy: ${CrystalEnergy.energyOf(player)}/${CrystalEnergy.MAX_ENERGY}"),
            )
            hideAdditionalTooltip(this)
        }

    private fun title(name: String): String =
        name.replaceFirstChar { it.uppercase() }

    /**
     * Destination screens are capped at [MAX_ROWS], just like player screens.
     * Destinations beyond the available slots do not fit and are not shown.
     */
    private fun destinationRows(count: Int): Int =
        (2 + ((count + ACTIONS_PER_ROW - 1) / ACTIONS_PER_ROW).coerceAtLeast(1)).coerceAtMost(MAX_ROWS)

    private fun actionSlots(count: Int): List<Int> =
        (0 until count).map { index ->
            (index / ACTIONS_PER_ROW + 1) * 9 + index % ACTIONS_PER_ROW + 2
        }

    private const val ACTIONS_PER_ROW = 6

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
        private val crystalCharges: Int,
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
            player.level().server.execute {
                // ...which leaves a window Nucleus did not have. Its close was
                // synchronous, so a second click arriving in the same tick found
                // the session already gone; here both clicks reach this queue
                // before either has closed anything, and a double-click would
                // teleport twice for two energy (or send two requests). This
                // menu no longer being the player's *is* that session having
                // ended, so a stale click is one whose menu has moved on.
                if (player.containerMenu === this) action(player)
            }
        }

        /**
         * Unreachable while [clicked] never delegates, and empty so it stays
         * that way if some other path ever asks: nothing in a crystal GUI moves.
         */
        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

        private fun actionFor(slot: Int): ((ServerPlayer) -> Unit)? = when (kind) {
            Kind.DESTINATIONS ->
                if (slot !in actionSlots(visibleDestinations().size)) {
                    null
                } else {
                    actionSlots(visibleDestinations().size)
                        .indexOf(slot)
                        .takeIf { it >= 0 }
                        ?.let { index -> { player -> choose(player, visibleDestinations()[index], crystalCharges) } }
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
