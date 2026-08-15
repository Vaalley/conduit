package eu.mctraveler.importer

import net.minecraft.nbt.CompoundTag

/**
 * The Nucleus-era crystal energy a Bukkit playerdata save carries (spec User
 * Story 39).
 *
 * Nucleus kept a player's energy in their Bukkit *persistent data container* —
 * `player.persistentDataContainer`, two `PersistentDataType.INTEGER` values
 * under its plugin's namespace. CraftBukkit writes a container into one
 * compound at the root of the save, keyed by the container's name and holding
 * each entry under its full namespaced key:
 *
 * ```
 * <root>
 *   BukkitValues:
 *     "mctravelernucleus:tc-teleportation-energy": 2
 *     "mctravelernucleus:tc-next-regen-at": 1234567
 * ```
 *
 * **Both spellings of that compound are read.** CraftBukkit names an *entity's*
 * container `BukkitValues` and an *item's* `PublicBukkitValues`, the two have
 * been confused in both directions across forks and versions, and the answer
 * cannot be checked without a real Bukkit save to hand. Reading whichever is
 * present costs one map lookup and removes the only way this importer could
 * silently carry nothing over; [Energy.container] reports which one answered so
 * the operator can see it in the summary rather than trust it.
 *
 * Both values are play-time ticks in Nucleus (`Statistic.PLAY_ONE_MINUTE`),
 * which is the same clock `CrystalEnergy` measures its own thresholds against,
 * so nothing is converted on the way in.
 */
object NucleusPlayerdata {

    /** Nucleus's `NamespacedKey(plugin, "tc-teleportation-energy")`, as NBT spells it. */
    const val ENERGY_KEY = "mctravelernucleus:tc-teleportation-energy"

    /** Nucleus's `NamespacedKey(plugin, "tc-next-regen-at")`. */
    const val NEXT_REGEN_AT_KEY = "mctravelernucleus:tc-next-regen-at"

    /** The root compounds a Bukkit persistent data container is written under. */
    val CONTAINER_KEYS = listOf("BukkitValues", "PublicBukkitValues")

    /**
     * What one save says about its player's crystal, or null when it says
     * nothing — the state of every player who never touched a crystal, and of
     * every save written after Nucleus was retired.
     */
    data class Energy(
        /** Points in the pool, or null if only a threshold was stored. */
        val energy: Int?,
        /** The play-time tick the next point was due at, or null when none was pending. */
        val nextRegenAt: Int?,
        /** Which of [CONTAINER_KEYS] the values were found under. */
        val container: String,
    )

    /** The crystal energy [tag] — one player's `<uuid>.dat` root — carries, if any. */
    fun energyOf(tag: CompoundTag): Energy? {
        for (key in CONTAINER_KEYS) {
            val container = tag.getCompound(key).orElse(null) ?: continue
            val energy = container.getInt(ENERGY_KEY).orElse(null)
            val nextRegenAt = container.getInt(NEXT_REGEN_AT_KEY).orElse(null)
            if (energy == null && nextRegenAt == null) continue
            return Energy(energy = energy, nextRegenAt = nextRegenAt, container = key)
        }
        return null
    }
}
