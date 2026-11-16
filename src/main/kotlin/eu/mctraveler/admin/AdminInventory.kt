package eu.mctraveler.admin

import eu.mctraveler.MCTraveler
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.ProblemReporter
import net.minecraft.world.ItemStackWithSlot
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput

/**
 * An admin's own inventory while admin mode has them in creative: written to
 * `mctraveler/admin-inventories/<uuid>.dat` *before* it is cleared, so a crash or
 * a restart in the middle of a session can never lose it. The file is the source
 * of truth — [AdminFeature] puts it back on `/admin`, on leaving, on shutdown and,
 * as the safety net for a crash, the next time the player joins.
 */
object AdminInventory {
    private val worn = listOf(
        EquipmentSlot.HEAD to "Head",
        EquipmentSlot.CHEST to "Chest",
        EquipmentSlot.LEGS to "Legs",
        EquipmentSlot.FEET to "Feet",
        EquipmentSlot.OFFHAND to "Offhand",
    )

    private fun file(server: MinecraftServer, uuid: UUID): Path =
        server.serverDirectory.resolve("mctraveler").resolve("admin-inventories").resolve("$uuid.dat")

    fun hasSaved(server: MinecraftServer, uuid: UUID): Boolean = Files.exists(file(server, uuid))

    /** Writes [player]'s inventory to disk and empties it. Throws, leaving the inventory alone, if it cannot be written. */
    fun stash(player: ServerPlayer) {
        val server = player.level().server
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess())
        player.inventory.save(output.list("Inventory", ItemStackWithSlot.CODEC))
        for ((slot, key) in worn) {
            output.store(key, ItemStack.OPTIONAL_CODEC, player.getItemBySlot(slot))
        }
        val target = file(server, player.uuid)
        Files.createDirectories(target.parent)
        val staging = target.resolveSibling("${target.fileName}.tmp")
        NbtIo.writeCompressed(output.buildResult(), staging)
        Files.move(staging, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        empty(player)
    }

    /** Puts the saved inventory back, replacing whatever the player holds now, and deletes the file. */
    fun restore(player: ServerPlayer): Boolean {
        val server = player.level().server
        val target = file(server, player.uuid)
        if (!Files.exists(target)) return false
        val tag = NbtIo.readCompressed(target, NbtAccounter.unlimitedHeap())
        val input = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), tag)
        empty(player)
        player.inventory.load(input.listOrEmpty("Inventory", ItemStackWithSlot.CODEC))
        for ((slot, key) in worn) {
            player.setItemSlot(slot, input.read(key, ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY))
        }
        Files.delete(target)
        player.containerMenu.broadcastFullState()
        player.inventoryMenu.broadcastFullState()
        return true
    }

    private fun empty(player: ServerPlayer) {
        player.inventory.clearContent()
        for ((slot, _) in worn) player.setItemSlot(slot, ItemStack.EMPTY)
        player.containerMenu.broadcastFullState()
        player.inventoryMenu.broadcastFullState()
    }

    internal fun log(message: String, failure: Exception) = MCTraveler.LOGGER.error(message, failure)
}
