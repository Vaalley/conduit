package eu.mctraveler.crystal

import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/**
 * The crystal's damage bar (spec User Story 23, deviation 12).
 *
 * Energy belongs to the player, not the item — one pool behind every crystal
 * they own — but the only place a client can be shown a fuel gauge is the item's
 * own damage bar. So the bar is painted on the way out: every crystal in a
 * container packet leaves wearing *that viewer's* energy, and the stored stack
 * never carries [DataComponents.DAMAGE] at all.
 *
 * This is what makes a crystal picked up from the floor, traded, or seen in a
 * shared container read correctly for whoever is looking at it. Nucleus did the
 * same thing through ProtocolLib's SET_SLOT and WINDOW_ITEMS adapters.
 */
object CrystalDamageDisplay {

    /**
     * [packet] as [viewer] should see it: [packet] itself unless it carries
     * crystals, in which case a copy is returned with each crystal's damage bar
     * showing [viewer]'s energy. Never mutates the packet or the stacks in it —
     * those stacks are the server's own inventory contents.
     *
     * Every clientbound packet in the game passes through here, so the cheap
     * "is there a crystal in this at all" test comes first and [viewer]'s energy
     * is read at most once, however many crystals a packet carries.
     */
    @JvmStatic
    fun forViewer(viewer: ServerPlayer, packet: Packet<*>): Packet<*> = when (packet) {
        is ClientboundContainerSetSlotPacket -> {
            if (!CrystalItem.isCrystal(packet.item)) {
                packet
            } else {
                ClientboundContainerSetSlotPacket(
                    packet.containerId,
                    packet.stateId,
                    packet.slot,
                    painted(packet.item, damageFor(viewer)),
                )
            }
        }

        is ClientboundContainerSetContentPacket -> {
            if (packet.items().none(CrystalItem::isCrystal) && !CrystalItem.isCrystal(packet.carriedItem())) {
                packet
            } else {
                val damage = damageFor(viewer)
                ClientboundContainerSetContentPacket(
                    packet.containerId(),
                    packet.stateId(),
                    packet.items().map { painted(it, damage) },
                    painted(packet.carriedItem(), damage),
                )
            }
        }

        // Since 1.21.4 the cursor and single player-inventory slots have their
        // own packets rather than riding inside the container ones, so leaving
        // these out would drop the bar off a crystal the moment it was picked
        // up onto the cursor.
        is ClientboundSetCursorItemPacket -> {
            if (!CrystalItem.isCrystal(packet.contents())) {
                packet
            } else {
                ClientboundSetCursorItemPacket(painted(packet.contents(), damageFor(viewer)))
            }
        }

        is ClientboundSetPlayerInventoryPacket -> {
            if (!CrystalItem.isCrystal(packet.contents())) {
                packet
            } else {
                ClientboundSetPlayerInventoryPacket(
                    packet.slot(),
                    painted(packet.contents(), damageFor(viewer)),
                )
            }
        }

        else -> packet
    }

    /**
     * The damage bar [viewer] should see on any crystal, read as "energy spent":
     * a full player's crystal shows no damage, an empty player's a full bar.
     */
    private fun damageFor(viewer: ServerPlayer): Int =
        CrystalEnergy.MAX_ENERGY - CrystalEnergy.energyOf(viewer)

    /** A crystal wearing [damage], or [stack] itself if it is not a crystal. */
    private fun painted(stack: ItemStack, damage: Int): ItemStack {
        if (!CrystalItem.isCrystal(stack)) return stack
        return stack.copy().apply { set(DataComponents.DAMAGE, damage) }
    }
}
