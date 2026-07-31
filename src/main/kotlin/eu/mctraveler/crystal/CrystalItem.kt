package eu.mctraveler.crystal

import eu.mctraveler.text.Paint
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore

/**
 * The Teleportation Crystal item (spec User Stories 20-24; Nucleus
 * `teleportation-crystal.kt`).
 *
 * A crystal is a re-skinned Echo Shard: vanilla item, vanilla registry, all the
 * crystal-ness carried in components. Identification follows the Notepad idiom
 * exactly — a marker in [DataComponents.CUSTOM_DATA] that no ordinary item can
 * carry — because the mod is server-only and must never add a registry entry a
 * vanilla client would not know.
 *
 * Presentation (name, lore, glint, stack size, charge capacity) lives on the
 * stored stack. The one thing that does not is the damage bar: energy is
 * per-player, not per-item, so [CrystalDamageDisplay] paints it onto outgoing
 * container packets and a stored crystal never carries [DataComponents.DAMAGE].
 */
object CrystalItem {

    /** The lowest tier, craftable from an Eye of Ender. */
    const val MIN_TIER = 1

    /** The highest tier, and the energy a crystal's damage bar is scaled against. */
    const val MAX_TIER = 3

    /** The crystal's item name, exactly as Nucleus named it. */
    const val ITEM_NAME = "Teleportation Crystal"

    /** Custom-data marker: present and true only on our crystals. */
    private const val MARKER = "is-teleportation-crystal"

    /** Custom-data tier, 1-3. */
    private const val TIER = "tier"

    /**
     * A fresh crystal of [tier]. Throws for a tier outside [MIN_TIER]..[MAX_TIER]
     * — every caller in the mod knows its tier statically, so an out-of-range one
     * is a bug, not user input.
     */
    fun of(tier: Int): ItemStack {
        require(tier in MIN_TIER..MAX_TIER) { "crystal tier must be $MIN_TIER..$MAX_TIER, was $tier" }
        val crystal = ItemStack(Items.ECHO_SHARD)
        CustomData.set(
            DataComponents.CUSTOM_DATA,
            crystal,
            CompoundTag().apply {
                putBoolean(MARKER, true)
                // A byte, not an int, so a crystal built here is byte-identical
                // to one the datapack recipes craft: JSON has no integer widths,
                // so the decoder narrows every small number to a byte. Reading
                // goes through getIntOr, which takes any numeric tag either way.
                putByte(TIER, tier.toByte())
            },
        )
        crystal.set(DataComponents.ITEM_NAME, Component.literal(ITEM_NAME))
        crystal.set(DataComponents.LORE, ItemLore(loreOf(tier)))
        crystal.set(DataComponents.MAX_STACK_SIZE, 1)
        // The damage bar is a fuel gauge, not wear: capacity is the tier, and how
        // full it looks is the viewer's own energy (see CrystalDamageDisplay).
        crystal.set(DataComponents.MAX_DAMAGE, tier)
        crystal.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return crystal
    }

    /** Nucleus's four lore lines, the last one gold. */
    private fun loreOf(tier: Int): List<Component> = listOf(
        Component.literal("The power of teleportation in your hands"),
        Component.literal("Recharges one use every ${CrystalEnergy.RECHARGE_MINUTES} minutes"),
        Component.literal(""),
        Paint.gold("Charge capacity $tier"),
    )

    /** True if [stack] is one of our crystals. Safe from any thread. */
    @JvmStatic
    fun isCrystal(stack: ItemStack): Boolean =
        stack.`is`(Items.ECHO_SHARD) &&
            stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getBooleanOr(MARKER, false) == true

    /**
     * The tier [stack] was crafted at. Nucleus read a missing tier as the top
     * one; kept, so a crystal from before the tier marker existed stays usable.
     * Meaningless for a stack [isCrystal] rejects.
     */
    @JvmStatic
    fun tierOf(stack: ItemStack): Int =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getIntOr(TIER, MAX_TIER) ?: MAX_TIER
}
