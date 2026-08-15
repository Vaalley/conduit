package eu.mctraveler.gametest

import com.mojang.authlib.GameProfile
import eu.mctraveler.MCTraveler
import eu.mctraveler.persistence.PersistenceService
import io.netty.channel.embedded.EmbeddedChannel
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps
import java.nio.file.Files
import java.util.Optional
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.DisconnectionDetails
import net.minecraft.network.HashedStack
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundEditBookPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB

/**
 * The Notepad (spec User Stories 25-27): `/notepad` opens the player's private
 * cross-World notebook as an editable book in their hand, saving persists the
 * pages and confirms, and the Portal's session guards reply their exact messages.
 *
 * Seam: the running server — commands dispatched as the player, the real
 * serverbound packets a vanilla client would send, and the chat/slot state the
 * player would see. Expected texts are the Portal's, from the feature inventory
 * (docs/research/portal-feature-inventory.md section 2.7).
 */
class NotepadGameTest {

    @GameTest
    fun notepadOpensTheWelcomeBookForFirstTimers(helper: GameTestHelper) {
        val visitor = NotepadTestPlayer.join(helper, "NotepadNewcomer")
        try {
            visitor.runCommand("notepad")
            val held = visitor.heldItem()
            helper.assertTrue(
                held.`is`(Items.WRITABLE_BOOK),
                "expected a writable book in the held slot, found $held",
            )
            helper.assertValueEqual(
                held.get(DataComponents.CUSTOM_NAME)?.string ?: "<unnamed>",
                "Click to edit your notepad",
                "the notepad book's name",
            )
            helper.assertValueEqual(
                pagesOf(held),
                listOf("This is your private note taking space. It's with you everywhere."),
                "the pages seeded for a first-time user",
            )
            helper.succeed()
        } finally {
            visitor.disconnect()
        }
    }

    @GameTest
    fun notepadOpensTheSavedPagesForReturningUsers(helper: GameTestHelper) {
        val visitor = NotepadTestPlayer.join(helper, "NotepadRegular")
        try {
            val saved = listOf("shopping list: obsidian", "coords: 71 64 -212")
            checkNotNull(MCTraveler.persistence).players.setNotepadPages(visitor.uuid, saved)
            visitor.runCommand("notepad")
            helper.assertValueEqual(
                pagesOf(visitor.heldItem()),
                saved,
                "the pages seeded from the player's saved notepad",
            )
            helper.succeed()
        } finally {
            visitor.disconnect()
        }
    }

    @GameTest
    fun notepadWhileEditingRepliesAlreadyEditing(helper: GameTestHelper) {
        val visitor = NotepadTestPlayer.join(helper, "NotepadDoubleTap")
        try {
            visitor.runCommand("notepad")
            visitor.clearReceived()
            visitor.runCommand("notepad")
            val replies = visitor.notepadReplies()
            helper.assertValueEqual(
                replies.map { it.string },
                listOf("You're already editing your notepad"),
                "the reply to /notepad while already editing",
            )
            helper.assertTrue(
                replies.single().style.color == TextColor.fromLegacyFormat(ChatFormatting.GRAY),
                "the already-editing reply should be gray, was styled ${replies.single().style}",
            )
            helper.assertTrue(
                visitor.heldItem().`is`(Items.WRITABLE_BOOK),
                "the open notepad book should still be in the held slot",
            )
            helper.succeed()
        } finally {
            visitor.disconnect()
        }
    }

    @GameTest
    fun savingTheBookPersistsPagesRestoresTheSlotAndConfirms(helper: GameTestHelper) {
        val visitor = NotepadTestPlayer.join(helper, "NotepadAuthor")
        visitor.setHeld(ItemStack(Items.STONE))
        visitor.runCommand("notepad")
        visitor.clearReceived()
        val edited = listOf("dear diary", "chapter two")
        visitor.player.connection.handleEditBook(
            ServerboundEditBookPacket(visitor.player.inventory.selectedSlot, edited, Optional.empty()),
        )
        helper.runAfterDelay(1) {
            try {
                helper.assertValueEqual(
                    checkNotNull(MCTraveler.persistence).players.notepadPages(visitor.uuid) ?: emptyList(),
                    edited,
                    "the pages in the player store after saving",
                )
                helper.assertValueEqual(
                    visitor.notepadReplies().map { it.string },
                    listOf("SUCCESS Notepad saved"),
                    "the reply to saving the notepad",
                )
                helper.assertTrue(
                    visitor.heldItem().`is`(Items.STONE),
                    "the held slot should hold the original item again, found ${visitor.heldItem()}",
                )
                helper.succeed()
            } finally {
                visitor.disconnect()
            }
        }
    }

    @GameTest
    fun savedPagesSurviveARestart(helper: GameTestHelper) {
        val visitor = NotepadTestPlayer.join(helper, "NotepadArchivist")
        visitor.runCommand("notepad")
        val edited = listOf("outlives the process")
        visitor.player.connection.handleEditBook(
            ServerboundEditBookPacket(visitor.player.inventory.selectedSlot, edited, Optional.empty()),
        )
        helper.runAfterDelay(1) {
            try {
                // A store built fresh over the server's directory sees only what
                // is on disk — exactly what a restarted server would load.
                val rebooted = PersistenceService(helper.level.server.serverDirectory.resolve("mctraveler"))
                helper.assertValueEqual(
                    rebooted.players.notepadPages(visitor.uuid) ?: emptyList(),
                    edited,
                    "the pages a freshly started store reads from disk",
                )
                helper.succeed()
            } finally {
                visitor.disconnect()
            }
        }
    }

    @GameTest
    fun aFailedSaveRepliesTheExactErrorAndStillRestoresTheSlot(helper: GameTestHelper) {
        val visitor = NotepadTestPlayer.join(helper, "NotepadUnlucky")
        visitor.setHeld(ItemStack(Items.STONE))
        visitor.runCommand("notepad")
        visitor.clearReceived()
        // Corrupt this player's record on disk: the store refuses to rewrite a
        // file it cannot parse, so the save below fails.
        val record = helper.level.server.serverDirectory.resolve("mctraveler/players/${visitor.uuid}.json")
        Files.createDirectories(record.parent)
        Files.writeString(record, "not json at all")
        visitor.player.connection.handleEditBook(
            ServerboundEditBookPacket(visitor.player.inventory.selectedSlot, listOf("doomed"), Optional.empty()),
        )
        helper.runAfterDelay(1) {
            try {
                helper.assertValueEqual(
                    visitor.notepadReplies().map { it.string },
                    listOf("ERROR Failed to save notepad"),
                    "the reply to a save the store rejected",
                )
                helper.assertTrue(
                    visitor.heldItem().`is`(Items.STONE),
                    "the held slot should hold the original item even after a failed save, found ${visitor.heldItem()}",
                )
                helper.succeed()
            } finally {
                Files.deleteIfExists(record)
                visitor.disconnect()
            }
        }
    }

    @GameTest
    fun changingTheHeldItemCancelsTheSession(helper: GameTestHelper) {
        val visitor = NotepadTestPlayer.join(helper, "NotepadFidgeter")
        try {
            visitor.setHeld(ItemStack(Items.STONE))
            val slot = visitor.player.inventory.selectedSlot
            visitor.runCommand("notepad")
            visitor.clearReceived()
            visitor.player.connection.handleSetCarriedItem(ServerboundSetCarriedItemPacket(slot + 2))
            helper.assertValueEqual(
                visitor.notepadReplies().map { it.string },
                listOf("ERROR Your notepad editing session has been cancelled"),
                "the reply to switching held item mid-edit",
            )
            helper.assertTrue(
                visitor.player.inventory.getItem(slot).`is`(Items.STONE),
                "the edited slot should hold the original item again",
            )
            helper.assertValueEqual(
                visitor.player.inventory.selectedSlot,
                slot + 2,
                "the selected slot after the switch vanilla still applies",
            )
            helper.assertTrue(
                !visitor.hasAnywhere(Items.WRITABLE_BOOK),
                "no notepad book may remain anywhere in the real inventory",
            )
            helper.assertTrue(
                checkNotNull(MCTraveler.persistence).players.notepadPages(visitor.uuid) == null,
                "a cancelled session must not have written the store",
            )
            helper.succeed()
        } finally {
            visitor.disconnect()
        }
    }

    @GameTest
    fun clickingTheInventoryCancelsTheSession(helper: GameTestHelper) {
        val visitor = NotepadTestPlayer.join(helper, "NotepadRummager")
        try {
            visitor.setHeld(ItemStack(Items.STONE))
            visitor.runCommand("notepad")
            visitor.clearReceived()
            val menu = visitor.player.containerMenu
            visitor.player.connection.handleContainerClick(
                ServerboundContainerClickPacket(
                    menu.containerId, menu.stateId, 9, 0,
                    ContainerInput.PICKUP, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY,
                ),
            )
            helper.assertValueEqual(
                visitor.notepadReplies().map { it.string },
                listOf("ERROR Your notepad editing session has been cancelled"),
                "the reply to clicking the inventory mid-edit",
            )
            helper.assertTrue(
                visitor.heldItem().`is`(Items.STONE),
                "the held slot should hold the original item again, found ${visitor.heldItem()}",
            )
            helper.assertTrue(
                !visitor.hasAnywhere(Items.WRITABLE_BOOK),
                "no notepad book may remain anywhere in the real inventory",
            )
            helper.succeed()
        } finally {
            visitor.disconnect()
        }
    }

    @GameTest
    fun swappingToTheOffhandCancelsTheSession(helper: GameTestHelper) {
        val visitor = NotepadTestPlayer.join(helper, "NotepadJuggler")
        try {
            visitor.setHeld(ItemStack(Items.STONE))
            visitor.runCommand("notepad")
            visitor.clearReceived()
            visitor.player.connection.handlePlayerAction(
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ZERO, Direction.DOWN,
                ),
            )
            helper.assertValueEqual(
                visitor.notepadReplies().map { it.string },
                listOf("ERROR Your notepad editing session has been cancelled"),
                "the reply to offhand-swapping mid-edit",
            )
            helper.assertTrue(
                !visitor.hasAnywhere(Items.WRITABLE_BOOK),
                "no notepad book may remain anywhere in the real inventory",
            )
            helper.assertTrue(
                visitor.hasAnywhere(Items.STONE),
                "the original item must survive the cancelled swap",
            )
            helper.succeed()
        } finally {
            visitor.disconnect()
        }
    }

    @GameTest
    fun dyingMidEditDropsTheOriginalItemNeverTheBook(helper: GameTestHelper) {
        val visitor = NotepadTestPlayer.join(helper, "NotepadMortal")
        try {
            // Die in survival, inside the test structure, where chunks are loaded
            // and drops land in view (mock players join in creative, which kill() spares).
            visitor.player.setGameMode(GameType.SURVIVAL)
            for (x in 0..4) for (z in 0..4) helper.setBlock(x, 0, z, Blocks.STONE_BRICKS)
            val spot = helper.absoluteVec(Vec3(2.0, 1.0, 2.0))
            visitor.player.teleportTo(spot.x, spot.y, spot.z)
            visitor.setHeld(ItemStack(Items.STONE))
            visitor.runCommand("notepad")
            visitor.clearReceived()
            val around = AABB.ofSize(spot, 12.0, 12.0, 12.0)
            visitor.player.kill(helper.level)
            val drops = helper.level.getEntitiesOfClass(ItemEntity::class.java, around) { true }
            helper.assertTrue(
                drops.none { it.item.`is`(Items.WRITABLE_BOOK) },
                "death must never drop the notepad book",
            )
            helper.assertTrue(
                drops.any { it.item.`is`(Items.STONE) },
                "death should drop the original held item",
            )
            helper.assertTrue(
                visitor.notepadReplies().isEmpty(),
                "death ends the session silently, with no notepad reply",
            )
            helper.succeed()
        } finally {
            visitor.disconnect()
        }
    }
}

/** The writable-book pages of [stack], as the reading client would see them. */
internal fun pagesOf(stack: ItemStack): List<String> =
    stack.get(DataComponents.WRITABLE_BOOK_CONTENT)?.getPages(false)?.toList() ?: emptyList()

/**
 * A player joined through the real login pipeline (`PlayerList.placeNewPlayer`)
 * over an in-memory netty channel — vanilla's own mock-server-player trick, with
 * the channel kept so tests can read the packets a real client would receive.
 */
internal class NotepadTestPlayer private constructor(
    val player: ServerPlayer,
    private val channel: EmbeddedChannel,
) {
    val uuid: UUID get() = player.uuid

    /** Runs a slash command exactly as if this player typed it. */
    fun runCommand(command: String) {
        val server = player.level().server
        server.commands.performPrefixedCommand(player.createCommandSourceStack(), command)
    }

    /** The item in the player's selected hotbar slot. */
    fun heldItem(): ItemStack = player.inventory.getItem(player.inventory.selectedSlot)

    /** Puts [stack] into the player's selected hotbar slot. */
    fun setHeld(stack: ItemStack) {
        player.inventory.setItem(player.inventory.selectedSlot, stack)
    }

    /** True if any slot of the player's inventory holds an item of this kind. */
    fun hasAnywhere(item: Item): Boolean =
        (0 until player.inventory.containerSize).any { player.inventory.getItem(it).`is`(item) }

    /**
     * The chat lines this player's client would have rendered since the last
     * drain, oldest first: system-chat packets on the wire, minus action-bar
     * overlays.
     */
    fun receivedChatLines(): List<Component> {
        val lines = mutableListOf<Component>()
        while (true) {
            val packet = channel.outboundMessages().poll() ?: break
            if (packet is ClientboundSystemChatPacket && !packet.overlay()) {
                lines += packet.content()
            }
        }
        return lines
    }

    /**
     * The notepad's replies to this player since the last drain. Filtered to the
     * notepad's message vocabulary because concurrently running tests race their
     * global join/leave/death broadcasts into every player's chat.
     */
    fun notepadReplies(): List<Component> = receivedChatLines().filter {
        val text = it.string
        text.startsWith("SUCCESS ") || text.startsWith("ERROR ") ||
            text == "You're already editing your notepad"
    }

    /** Drops everything received so far (join-time noise) before the action under test. */
    fun clearReceived() {
        channel.outboundMessages().clear()
    }

    /** Disconnects through the real path, as if the client's connection dropped. */
    fun disconnect() {
        player.connection.onDisconnect(DisconnectionDetails(Component.literal("test over")))
    }

    companion object {
        fun join(helper: GameTestHelper, name: String): NotepadTestPlayer {
            val server = helper.level.server
            val cookie = CommonListenerCookie.createInitial(GameProfile(UUID.randomUUID(), name), false)
            val player = ServerPlayer(server, helper.level, cookie.gameProfile(), cookie.clientInformation())
            val connection = Connection(PacketFlow.SERVERBOUND)
            val channel = EmbeddedChannel(connection)
            server.playerList.placeNewPlayer(connection, player, cookie)
            // What a real client sends once its level renders; without it the
            // server keeps the player invulnerable (and so unkillable in tests).
            player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
            return NotepadTestPlayer(player, channel).also { it.clearReceived() }
        }
    }
}
