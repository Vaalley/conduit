package eu.mctraveler.notepad

import com.mojang.brigadier.Command
import eu.mctraveler.MCTraveler
import eu.mctraveler.persistence.PlayerStore
import eu.mctraveler.text.Paint
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.Commands
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.Filterable
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.WritableBookContent

/**
 * The Notepad: a private, cross-World notebook every player carries (spec User
 * Stories 25-27, Portal inventory section 2.7).
 *
 * `/notepad` starts an edit session: the player's held hotbar slot is
 * temporarily replaced with a real writable book carrying their saved pages
 * (the Portal faked this book client-side; on the server we can place a real
 * one), and the original slot contents are held in the session. Pressing Done
 * in the book screen sends the vanilla edit-book packet, which
 * [eu.mctraveler.mixin.ServerGamePacketListenerImplMixin] routes here instead
 * of letting vanilla write the book: the slot is restored, the pages persist to
 * the player store, and the player gets the Portal's confirmation. Anything
 * that would let the stand-in book escape the slot — switching held item,
 * clicking the inventory, dropping or offhand-swapping it, dying, logging out —
 * ends the session first and restores the original item, so the book never
 * exists outside an active session.
 */
object NotepadFeature {

    /** The Portal's default first page for players who never saved a notepad. */
    const val WELCOME_PAGE = "This is your private note taking space. It's with you everywhere."

    /** The stand-in book's display name, exactly as the Portal named its fake book. */
    const val BOOK_NAME = "Click to edit your notepad"

    /** Custom-data marker identifying our stand-in book, so it can never be mistaken for a player's real book. */
    private const val MARKER = "mctraveler_notepad"

    /** An active edit session: which hotbar slot holds the stand-in book, and what it displaced. */
    private class Session(val slot: Int, val original: ItemStack)

    /**
     * Sessions by player uuid. Mutated only on the server thread; concurrent
     * because the edit-book packet checks for a session on the netty thread.
     */
    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("notepad").executes { context ->
                    open(context.source.playerOrException)
                    Command.SINGLE_SUCCESS
                },
            )
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            endSessionQuietly(handler.player)
        }
        // A crash mid-session can persist the stand-in book into saved playerdata;
        // sweep any such stray out when the player is next placed.
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            removeEditingBooks(handler.player)
        }
        // Death would drop the whole inventory, stand-in book included: restore
        // the real item first (silently — the Portal's cancellation error is for
        // deliberate edits, not dying) so death loot is the player's own.
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            if (entity is ServerPlayer) endSessionQuietly(entity)
            true
        }
        // The final playerdata save must never contain the stand-in book.
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            server.playerList.players.toList().forEach(::endSessionQuietly)
        }
        // Belt and braces: if the book leaves its slot through any path the
        // packet hooks don't see (creative surgery, another mod), cancel.
        ServerTickEvents.END_SERVER_TICK.register { server ->
            for ((uuid, session) in sessions) {
                val player = server.playerList.getPlayer(uuid)
                if (player == null) {
                    sessions.remove(uuid) // gone without a disconnect: nothing left to restore into
                } else if (
                    player.inventory.selectedSlot != session.slot ||
                    !isEditingBook(player.inventory.getItem(session.slot))
                ) {
                    cancelSession(player)
                }
            }
        }
    }

    /** `/notepad`: opens the player's notepad for editing, or reminds them it is already open. */
    fun open(player: ServerPlayer) {
        if (sessions.containsKey(player.uuid)) {
            player.sendSystemMessage(Paint.gray("You're already editing your notepad"))
            return
        }
        val pages = store.notepadPages(player.uuid) ?: listOf(WELCOME_PAGE)
        val inventory = player.inventory
        val slot = inventory.selectedSlot
        sessions[player.uuid] = Session(slot, inventory.getItem(slot).copy())
        inventory.setItem(slot, editingBook(pages))
    }

    /** True if the player is mid-edit. Safe from any thread. */
    @JvmStatic
    fun hasSession(player: ServerPlayer): Boolean = sessions.containsKey(player.uuid)

    /**
     * The player pressed Done in the book screen: ends the session (restoring
     * the real slot contents, as the Portal's resync did in every outcome) and
     * persists [pages], confirming with the Portal's exact success or error.
     * Server thread only. No-ops if the session ended before the edit arrived.
     */
    @JvmStatic
    fun completeSession(player: ServerPlayer, pages: List<String>) {
        if (!endSession(player)) return
        runCatching { store.setNotepadPages(player.uuid, pages) }
            .onSuccess { player.sendSystemMessage(Paint.success("Notepad saved")) }
            .onFailure { failure ->
                MCTraveler.LOGGER.error("Failed to save {}'s notepad", player.gameProfile.name, failure)
                player.sendSystemMessage(Paint.error("Failed to save notepad"))
            }
    }

    /**
     * Ends the session with the Portal's cancellation error — the reply to the
     * player doing anything mid-edit that would move the stand-in book (held
     * item change, inventory click, drop, offhand swap). Server thread only.
     * No-ops if the player has no session.
     */
    @JvmStatic
    fun cancelSession(player: ServerPlayer) {
        if (endSession(player)) {
            player.sendSystemMessage(Paint.error("Your notepad editing session has been cancelled"))
        }
    }

    /** Ends the session without a message (logout, death): the player never sees the book go. */
    fun endSessionQuietly(player: ServerPlayer) {
        endSession(player)
    }

    /**
     * Takes the player's session, if any, and puts the displaced original back:
     * into its slot if the stand-in book is still there, or — if the book
     * escaped through a path we don't hook — into any free space. Either way
     * every marked book is swept from the inventory, so nothing of the stand-in
     * survives the session. Returns whether there was a session to end.
     */
    private fun endSession(player: ServerPlayer): Boolean {
        val session = sessions.remove(player.uuid) ?: return false
        val inventory = player.inventory
        val slotHeldTheBook = isEditingBook(inventory.getItem(session.slot))
        removeEditingBooks(player)
        if (slotHeldTheBook) {
            inventory.setItem(session.slot, session.original)
        } else if (!session.original.isEmpty) {
            inventory.placeItemBackInInventory(session.original)
        }
        return true
    }

    /** The stand-in book: a real writable book carrying the player's pages, marked as ours. */
    private fun editingBook(pages: List<String>): ItemStack {
        val book = ItemStack(Items.WRITABLE_BOOK)
        book.set(DataComponents.CUSTOM_NAME, Component.literal(BOOK_NAME))
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent(pages.map { Filterable.passThrough(it) }))
        CustomData.set(DataComponents.CUSTOM_DATA, book, CompoundTag().apply { putBoolean(MARKER, true) })
        return book
    }

    private fun isEditingBook(stack: ItemStack): Boolean =
        stack.`is`(Items.WRITABLE_BOOK) &&
            stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getBooleanOr(MARKER, false) == true

    private fun removeEditingBooks(player: ServerPlayer) {
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            if (isEditingBook(inventory.getItem(slot))) inventory.setItem(slot, ItemStack.EMPTY)
        }
    }

    private val store: PlayerStore
        get() = checkNotNull(MCTraveler.persistence) { "the Notepad needs the Persistence service" }.players
}
