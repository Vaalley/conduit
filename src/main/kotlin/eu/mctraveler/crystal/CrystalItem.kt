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

    /** The highest tier. */
    const val MAX_TIER = 3

    /** The crystal's item name, exactly as Nucleus named it. */
    const val ITEM_NAME = "Teleportation Crystal"

    /** Custom-data marker: present and true only on our crystals. */
    private const val MARKER = "is-teleportation-crystal"

    /** Custom-data tier, 1-3. */
    private const val TIER = "tier"

    // ---- the Nucleus-era layout (spec deviation 18) ----
    //
    // Bukkit's PersistentDataContainer serialises into its own compound inside
    // custom_data, keyed by namespaced string. Crystals minted before the port
    // are still sitting in migrated inventories, chests, ender chests and
    // shulkers, and no importer sweep can reach every container — so a crystal
    // is whatever wears *either* marker, and everything downstream (the menu,
    // the damage bar, the crafting guard) treats the two alike.

    /** Bukkit's persistent-data compound inside [DataComponents.CUSTOM_DATA]. */
    private const val BUKKIT_VALUES = "PublicBukkitValues"

    /** Nucleus's `is-teleportation-crystal` key, namespaced as Bukkit wrote it. */
    private const val LEGACY_MARKER = "mctravelernucleus:is-teleportation-crystal"

    /** Nucleus's `teleportation-crystal-tier` key, namespaced as Bukkit wrote it. */
    private const val LEGACY_TIER = "mctravelernucleus:teleportation-crystal-tier"

    /**
     * A fresh crystal of [tier]. Throws for a tier outside [MIN_TIER]..[MAX_TIER]
     * — a tier is never user input, so an out-of-range one is a bug.
     *
     * Players get their crystals from the datapack recipes rather than from
     * here; this is the definition those recipes' `result` blocks are held to
     * (see `CrystalRecipeJsonTest`), and the crystal any later feature needs to
     * hand out directly.
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
        dress(crystal, tier)
        return crystal
    }

    /**
     * A copy of [stack] wearing everything that makes a crystal look like one.
     *
     * The presentation lives on the stored stack for crystals this mod minted,
     * but a Nucleus-era one (spec deviation 18) carries only Bukkit's marker:
     * no name, no lore, no glint, stacking to 64, and — fatally for the energy
     * gauge — no `MAX_DAMAGE`, without which a client draws no bar at all. So
     * the presentation is (re)applied on the way out, alongside the damage
     * ([CrystalDamageDisplay]), which is exactly what Nucleus's
     * `updateItemEnergy` did to every crystal on every SET_SLOT and
     * WINDOW_ITEMS. Idempotent for a minted crystal, restorative for a legacy
     * one.
     */
    @JvmStatic
    fun presented(stack: ItemStack): ItemStack = stack.copy().also { dress(it, tierOf(it)) }

    /** Writes the crystal's presentation onto [stack] in place. */
    private fun dress(stack: ItemStack, tier: Int) {
        stack.set(DataComponents.ITEM_NAME, Component.literal(ITEM_NAME))
        stack.set(DataComponents.LORE, ItemLore(loreOf(tier)))
        stack.set(DataComponents.MAX_STACK_SIZE, 1)
        // The damage bar is a fuel gauge, not wear: capacity is the charges, and how
        // full it looks is the viewer's own energy (see CrystalDamageDisplay).
        stack.set(DataComponents.MAX_DAMAGE, chargesOf(tier))
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
    }

    /** Nucleus's four lore lines, the last one gold. */
    private fun loreOf(tier: Int): List<Component> = listOf(
        Component.literal("The power of teleportation in your hands"),
        Component.literal("Recharges one use every ${CrystalEnergy.RECHARGE_MINUTES} minutes"),
        Component.literal(""),
        Paint.gold("Charge capacity ${chargesOf(tier)}"),
    )

    /** The number of charges available from a crystal of [tier]. */
    @JvmStatic
    fun chargesOf(tier: Int): Int {
        require(tier in MIN_TIER..MAX_TIER) { "crystal tier must be $MIN_TIER..$MAX_TIER, was $tier" }
        return when (tier) {
            1 -> 1
            2 -> 3
            3 -> 5
            else -> error("unreachable")
        }
    }

    /**
     * True if [stack] is one of our crystals — minted here, or by Nucleus
     * before the port (see [BUKKIT_VALUES]). Safe from any thread.
     */
    @JvmStatic
    fun isCrystal(stack: ItemStack): Boolean {
        if (!stack.`is`(Items.ECHO_SHARD)) return false
        val data = customData(stack) ?: return false
        return data.getBooleanOr(MARKER, false) ||
            data.getCompoundOrEmpty(BUKKIT_VALUES).getBooleanOr(LEGACY_MARKER, false)
    }

    /**
     * The tier [stack] was crafted at, in whichever layout it carries. Nucleus
     * read a missing tier as the top one; kept, so a crystal from before the
     * tier marker existed stays usable. Meaningless for a stack [isCrystal]
     * rejects.
     */
    @JvmStatic
    fun tierOf(stack: ItemStack): Int {
        val data = customData(stack) ?: return MAX_TIER
        return data.getInt(TIER)
            .or { data.getCompoundOrEmpty(BUKKIT_VALUES).getInt(LEGACY_TIER) }
            .orElse(MAX_TIER)
    }

    private fun customData(stack: ItemStack): CompoundTag? =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()
}
