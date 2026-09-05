package eu.mctraveler.region

import net.minecraft.world.item.Item
import net.minecraft.world.item.Items

/**
 * The Portal's flag vocabulary as a single catalog, replacing the ad-hoc
 * per-flag constants and mixed `in`/`!in` polarity that used to be scattered
 * across [RegionProtection] and [RegionEnvironment].
 *
 * **Uniform presence semantics.** For every flag, `id in region.flags` means
 * "allowed" — full stop. A [Definition.defaultAllowed] flag is seeded into a
 * new region's `flags` at creation time ([seedDefaults]), so its absence only
 * ever happens via an explicit disable; a flag that defaults to disallowed is
 * simply never seeded. No enforcement site branches on a flag's own polarity
 * any more — every call site is a flat `"<ID>" in region.flags` check.
 *
 * This file is also the region-flags GUI's data: [MAIN] and [ADMIN] are the
 * two 27-slot pages [RegionFlagsMenu] draws, in display order.
 */
object RegionFlags {

    /** How a flag's current state reads in the GUI's status line. */
    enum class StatusStyle { ALLOWED_DISALLOWED, TRUE_FALSE }

    data class Definition(
        val id: String,
        val icon: Item,
        val defaultAllowed: Boolean,
        val label: String,
        val description: List<String>,
        val statusStyle: StatusStyle = StatusStyle.ALLOWED_DISALLOWED,
        val adminOnly: Boolean = false,
        /** False only for `EMBASSY`, which the admin page shows but never toggles. */
        val toggleable: Boolean = true,
    )

    /** The 16-item main page — resident/owner toggleable, grouped by theme. */
    val MAIN: List<Definition> = listOf(
        // Row 1 — building & access
        Definition(
            id = "EXPLOSIONS",
            icon = Items.TNT,
            defaultAllowed = false,
            label = "Explosions",
            description = listOf("Allows explosions to damage blocks in this region"),
        ),
        Definition(
            id = "FIRE_DAMAGE",
            icon = Items.CAMPFIRE,
            defaultAllowed = false,
            label = "Fire damage",
            description = listOf("Allows fire to burn and spread into this region"),
        ),
        Definition(
            id = "GATES",
            icon = Items.OAK_FENCE_GATE,
            defaultAllowed = true,
            label = "Gates",
            description = listOf("Allows non-members to open fence gates"),
        ),
        Definition(
            id = "DOORS",
            icon = Items.OAK_DOOR,
            defaultAllowed = true,
            label = "Doors",
            description = listOf("Allows non-members to open doors"),
        ),
        Definition(
            id = "TRAPDOORS",
            icon = Items.OAK_TRAPDOOR,
            defaultAllowed = true,
            label = "Trapdoors",
            description = listOf("Allows non-members to open trapdoors"),
        ),
        Definition(
            id = "PUBLIC_CONTAINERS",
            icon = Items.CHEST,
            defaultAllowed = false,
            label = "Public containers",
            description = listOf("Allows non-members to use chests and other containers"),
        ),
        // Row 2 — automation & transport
        Definition(
            id = "PUBLIC_REDSTONE",
            icon = Items.REDSTONE,
            defaultAllowed = true,
            label = "Public redstone",
            description = listOf("Allows non-members to use buttons, levers and pressure plates"),
        ),
        Definition(
            id = "WEIGHTED_PRESSURE_PLATES",
            icon = Items.LIGHT_WEIGHTED_PRESSURE_PLATE,
            defaultAllowed = true,
            label = "Weighted pressure plates",
            description = listOf("Allows weighted pressure plates to trigger in this region"),
        ),
        Definition(
            id = "MINECARTS",
            icon = Items.MINECART,
            defaultAllowed = true,
            label = "Minecarts",
            description = listOf("Allows non-members to break and place empty minecarts"),
        ),
        Definition(
            id = "BOATS",
            icon = Items.OAK_BOAT,
            defaultAllowed = true,
            label = "Boats",
            description = listOf("Allows non-members to break and place boats"),
        ),
        Definition(
            id = "RIDEABLE",
            icon = Items.LEATHER_HORSE_ARMOR,
            defaultAllowed = true,
            label = "Rideable",
            description = listOf("Allows non-members to ride horses, camels, pigs and nautiluses"),
        ),
        // Row 3 — mobs & combat
        Definition(
            id = "PUBLIC_VILLAGERS",
            icon = Items.VILLAGER_SPAWN_EGG,
            defaultAllowed = false,
            label = "Public villagers",
            description = listOf("Allows non-members to trade with villagers"),
        ),
        Definition(
            id = "ANIMAL_PROTECTION",
            icon = Items.SHEEP_SPAWN_EGG,
            defaultAllowed = true,
            label = "Animal protection",
            description = listOf("Protects animals and mobs in this region from non-members"),
            statusStyle = StatusStyle.TRUE_FALSE,
        ),
        Definition(
            id = "PVP",
            icon = Items.DIAMOND_SWORD,
            defaultAllowed = true,
            label = "PvP",
            description = listOf("Allows player-versus-player combat in this region"),
        ),
        Definition(
            id = "WIND_CHARGES",
            icon = Items.WIND_CHARGE,
            defaultAllowed = false,
            label = "Wind charges",
            description = listOf("Allows non-members' wind charges to affect players and animals"),
        ),
        Definition(
            id = "POTIONS",
            icon = Items.SPLASH_POTION,
            defaultAllowed = false,
            label = "Potions",
            description = listOf("Allows use of potions by others"),
        ),
    )

    /** The 5-item admin-only page. */
    val ADMIN: List<Definition> = listOf(
        Definition(
            id = "SCOREBOARD",
            icon = Items.PAINTING,
            defaultAllowed = true,
            label = "Scoreboard",
            description = listOf("Shows the residents sidebar while standing in this region"),
            statusStyle = StatusStyle.TRUE_FALSE,
            adminOnly = true,
        ),
        Definition(
            id = "ADMIN",
            icon = Items.COMMAND_BLOCK,
            defaultAllowed = false,
            label = "Admin",
            description = listOf("Marks this region as an admin region"),
            statusStyle = StatusStyle.TRUE_FALSE,
            adminOnly = true,
        ),
        Definition(
            id = "PUBLIC",
            icon = Items.PLAYER_HEAD,
            defaultAllowed = false,
            label = "Public",
            description = listOf("Opens this region up to everyone, as if they were a member"),
            statusStyle = StatusStyle.TRUE_FALSE,
            adminOnly = true,
        ),
        Definition(
            id = "FALL_DAMAGE",
            icon = Items.FEATHER,
            defaultAllowed = true,
            label = "Fall damage",
            description = listOf("Protects players standing in this region from fall damage"),
            statusStyle = StatusStyle.TRUE_FALSE,
            adminOnly = true,
        ),
        Definition(
            id = Region.EMBASSY_FLAG,
            icon = Items.SPYGLASS,
            defaultAllowed = false,
            label = "Embassy",
            description = listOf("This region is an embassy"),
            statusStyle = StatusStyle.TRUE_FALSE,
            adminOnly = true,
            toggleable = false,
        ),
    )

    /** Every flag id, main page then admin page — the Portal's full vocabulary. */
    val ALL: List<Definition> = MAIN + ADMIN

    fun byId(id: String): Definition? = ALL.firstOrNull { it.id == id }

    /** Seeds a freshly created region's flags with every `defaultAllowed` flag. */
    fun seedDefaults(region: Region) {
        for (def in ALL) if (def.defaultAllowed) region.flags.add(def.id)
    }

    /**
     * Remaps every legacy flag string in [flags] to its new-catalog equivalent,
     * in place, then fills in the seeded baseline for brand-new flags a region
     * saved before this rework never had a chance to carry.
     *
     * Meant to run exactly once per region, right after its flags are parsed
     * from storage — not safe to call a second time on the same set: several
     * rows (`NO_SCOREBOARD`, `DISABLE_GATES`, `DISABLE_PVP`, …) turn "neither
     * the old nor the new flag is present" into "add the new one", which is
     * the correct reading for a legacy blob that never mentioned the old flag
     * at all, but is indistinguishable from "already migrated to absent" on a
     * second pass. [RegionStore] calls this once per parsed region and never
     * again for that in-memory `Region`.
     */
    fun migrateLegacy(flags: MutableSet<String>) {
        remapRenamed(flags, "ENABLE_EXPLOSIONS", "EXPLOSIONS")
        remapRenamed(flags, "ENABLE_PUBLIC_CONTAINERS", "PUBLIC_CONTAINERS")
        remapRenamed(flags, "ENABLE_FIRE_DAMAGE", "FIRE_DAMAGE")
        remapRenamed(flags, "ENABLE_PUBLIC_VILLAGER_TRADING", "PUBLIC_VILLAGERS")

        // NO_SCOREBOARD present -> board hidden -> SCOREBOARD absent (and vice versa).
        if (flags.remove("NO_SCOREBOARD")) {
            flags.remove("SCOREBOARD")
        } else {
            flags.add("SCOREBOARD")
        }

        // DISABLE_GATES present -> all three new door-shaped flags stay absent;
        // absent -> all three were "seeded" (present).
        if (flags.remove("DISABLE_GATES")) {
            flags.remove("GATES")
            flags.remove("DOORS")
            flags.remove("TRAPDOORS")
        } else {
            flags.add("GATES")
            flags.add("DOORS")
            flags.add("TRAPDOORS")
        }

        // DISABLE_PLAYER_FALL_DAMAGE present ("no fall damage") -> FALL_DAMAGE
        // present ("protected from fall damage"): both mean the same outcome.
        if (flags.remove("DISABLE_PLAYER_FALL_DAMAGE")) {
            flags.add("FALL_DAMAGE")
        }
        // else: leave FALL_DAMAGE absent from migration; the seeded-baseline
        // pass below adds it, since a region without the old flag always let
        // fall damage through under the new default-true baseline too.

        // DISABLE_PUBLIC_REDSTONE_TRIGGERS present -> PUBLIC_REDSTONE absent;
        // absent -> PUBLIC_REDSTONE was seeded (present).
        if (flags.remove("DISABLE_PUBLIC_REDSTONE_TRIGGERS")) {
            flags.remove("PUBLIC_REDSTONE")
        } else {
            flags.add("PUBLIC_REDSTONE")
        }

        // DISABLE_WEIGHTED_PRESSURE_PLATES present -> flag absent; absent -> seeded.
        if (flags.remove("DISABLE_WEIGHTED_PRESSURE_PLATES")) {
            flags.remove("WEIGHTED_PRESSURE_PLATES")
        } else {
            flags.add("WEIGHTED_PRESSURE_PLATES")
        }

        // DISABLE_ANIMAL_PROTECTION present (unprotected) -> ANIMAL_PROTECTION
        // absent; absent (protected) -> ANIMAL_PROTECTION present.
        if (flags.remove("DISABLE_ANIMAL_PROTECTION")) {
            flags.remove("ANIMAL_PROTECTION")
        } else {
            flags.add("ANIMAL_PROTECTION")
        }

        // DISABLE_PVP present (no pvp) -> PVP absent; absent (pvp allowed) -> PVP present.
        if (flags.remove("DISABLE_PVP")) {
            flags.remove("PVP")
        } else {
            flags.add("PVP")
        }

        // PUBLIC, ADMIN, EMBASSY: unchanged strings, nothing to remap.

        // Baseline every brand-new flag not implied by any migration above, so
        // an old region ends up exactly where a new one starts, before the
        // remaps above layer any explicit legacy disable on top.
        for (def in ALL) {
            if (def.defaultAllowed && def.id !in DERIVED_FROM_LEGACY) {
                flags.add(def.id)
            }
        }
    }

    /** Flags whose presence/absence is always fully decided by [migrateLegacy] above. */
    private val DERIVED_FROM_LEGACY = setOf(
        "SCOREBOARD", "GATES", "DOORS", "TRAPDOORS", "PUBLIC_REDSTONE",
        "WEIGHTED_PRESSURE_PLATES", "ANIMAL_PROTECTION", "PVP",
    )

    private fun remapRenamed(flags: MutableSet<String>, old: String, new: String) {
        if (flags.remove(old)) flags.add(new)
    }
}
