package eu.mctraveler.crystal

import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
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
     * [packet] as [viewer] should see it: unchanged unless it carries crystals,
     * in which case a copy is returned with each crystal's damage bar showing
     * [viewer]'s energy. Never mutates the packet or the stacks in it — those
     * stacks are the server's own inventory contents.
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
                    painted(packet.item, viewer),
                )
            }
        }

        is ClientboundContainerSetContentPacket -> {
            if (packet.items().none(CrystalItem::isCrystal) && !CrystalItem.isCrystal(packet.carriedItem())) {
                packet
            } else {
                ClientboundContainerSetContentPacket(
                    packet.containerId(),
                    packet.stateId(),
                    packet.items().map { painted(it, viewer) },
                    painted(packet.carriedItem(), viewer),
                )
            }
        }

        else -> packet
    }

    /**
     * A crystal wearing [viewer]'s energy, or [stack] itself if it is not one.
     * The bar is read as "energy spent": a full player's crystal shows no damage,
     * an empty player's shows a full bar.
     */
    private fun painted(stack: ItemStack, viewer: ServerPlayer): ItemStack {
        if (!CrystalItem.isCrystal(stack)) return stack
        return stack.copy().apply {
            set(DataComponents.DAMAGE, CrystalEnergy.MAX_ENERGY - CrystalEnergy.energyOf(viewer))
        }
    }
}
