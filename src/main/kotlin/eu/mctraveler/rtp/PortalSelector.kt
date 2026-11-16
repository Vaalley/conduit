package eu.mctraveler.rtp

import eu.mctraveler.admin.StaffAccess
import eu.mctraveler.text.Paint
import java.util.UUID
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.Level

/**
 * The "Portal selector": an enchanted brush that picks the two corners of a wilderness
 * portal the way a WorldEdit wand does — left click for the first corner, right click for
 * the second — and then asks, in chat, whether to build the portal there.
 *
 * Only staff in admin mode can use one, and only in the overworld or the End.
 */
object PortalSelector {

    private const val TAG = "mctraveler_portal_selector"

    private class Selection(val dimension: ResourceKey<Level>) {
        /** What the chat buttons carry; a new pair of corners gets a new one. */
        var token: String = ""
        var first: BlockPos? = null
        var second: BlockPos? = null
    }

    private val selections = HashMap<UUID, Selection>()

    fun register() {
        AttackBlockCallback.EVENT.register { player, _, hand, pos, _ ->
            if (player is ServerPlayer && hand == InteractionHand.MAIN_HAND && holdsTool(player)) {
                select(player, pos, first = true)
                InteractionResult.FAIL
            } else {
                InteractionResult.PASS
            }
        }
        UseBlockCallback.EVENT.register { player, _, hand, hit ->
            if (player is ServerPlayer && holdsTool(player)) {
                // Both hands fire; answer the main hand and swallow the other so the brush never brushes.
                if (hand == InteractionHand.MAIN_HAND) select(player, hit.blockPos, first = false)
                InteractionResult.SUCCESS_SERVER
            } else {
                InteractionResult.PASS
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> selections.remove(handler.player.uuid) }
        ServerLifecycleEvents.SERVER_STOPPED.register { selections.clear() }
    }

    /** A fresh Portal selector. */
    fun tool(): ItemStack {
        val stack = ItemStack(Items.BRUSH)
        stack.set(
            DataComponents.CUSTOM_NAME,
            Component.literal("Portal selector").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
        )
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.putBoolean(TAG, true) }))
        return stack
    }

    fun isTool(stack: ItemStack): Boolean =
        stack.`is`(Items.BRUSH) && stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getBooleanOr(TAG, false) == true

    private fun holdsTool(player: Player): Boolean = isTool(player.mainHandItem)

    private fun select(player: ServerPlayer, pos: BlockPos, first: Boolean) {
        if (!StaffAccess.isActive(player)) {
            player.sendSystemMessage(Paint.error("The Portal selector only works in admin mode"))
            return
        }
        val dimension = player.level().dimension()
        if (dimension != Level.OVERWORLD && dimension != Level.END) {
            player.sendSystemMessage(Paint.error("Wilderness portals only stand in the overworld or the End"))
            return
        }
        val selection = selections[player.uuid]?.takeIf { it.dimension == dimension }
            ?: Selection(dimension).also { selections[player.uuid] = it }
        if (first) selection.first = pos else selection.second = pos
        val label = if (first) "First" else "Second"
        val a = selection.first
        val b = selection.second
        val size = if (a != null && b != null) PortalBox.of(a, b) else null
        player.sendSystemMessage(
            Paint.lightPurple(
                "$label position set to (${pos.x}, ${pos.y}, ${pos.z})",
                size?.let { " (${it.volume} blocks)" } ?: "",
                ".",
            ),
        )
        if (size != null) {
            selection.token = UUID.randomUUID().toString().substring(0, 8)
            player.sendSystemMessage(
                Paint(
                    Paint.gray("Make this a wilderness portal? "),
                    Paint.green.bold.runs("/wilderness portal accept ${selection.token}")("[Accept]"),
                    " ",
                    Paint.red.bold.runs("/wilderness portal cancel ${selection.token}")("[Cancel]"),
                ),
            )
        }
    }

    /**
     * The [Accept] button: builds the portal from the two selected corners. The command is
     * only ever run by clicking, since it needs the [token] the button for this selection carries.
     */
    fun accept(player: ServerPlayer, token: String): Component {
        val selection = selections[player.uuid]?.takeIf { it.dimension == player.level().dimension() && it.token == token }
        val a = selection?.first
        val b = selection?.second
        if (a == null || b == null) return Paint.error("Select two corners with the Portal selector first")
        val box = PortalBox.of(a, b)
        if (box.volume > WildernessPortals.MAX_VOLUME) {
            return Paint.error("That is ${box.volume} blocks; a portal can be at most ${WildernessPortals.MAX_VOLUME}")
        }
        val level = player.level()
        if (WildernessPortals.overlaps(level, box)) {
            return Paint.error("That overlaps another wilderness portal")
        }
        selections.remove(player.uuid)
        WildernessPortals.create(level, box)
        return Paint.success("Wilderness portal created (${box.volume} blocks). Walk into the water to travel.")
    }

    /** The [Cancel] button: forgets the selection. */
    fun cancel(player: ServerPlayer, token: String): Component {
        val selection = selections[player.uuid]
        if (selection == null || selection.token != token) return Paint.error("You have nothing selected")
        selections.remove(player.uuid)
        return Paint.success("Selection cleared")
    }

    /** `/wilderness portal create`: hands over the tool. */
    fun give(player: ServerPlayer): Component {
        if (player.inventory.contains { isTool(it) }) return Paint.error("You already have a Portal selector")
        if (!player.inventory.add(tool())) return Paint.error("Your inventory is full")
        return Paint.success(
            "Left click a block for the first corner, right click for the second. ",
            "Then press Accept in chat.",
        )
    }
}
