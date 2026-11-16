package eu.mctraveler.crystal

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.crafting.Recipe

/**
 * The Teleportation Crystal recipes always sit in every player's recipe book.
 *
 * Datapack recipes are unlocked by an advancement or a command, and ours have neither, so a
 * player would only ever see them after something else happened to unlock them. They are
 * handed out at login instead; awarding what the player already has changes nothing.
 */
object CrystalRecipes {

    val KEYS: List<ResourceKey<Recipe<*>>> = (CrystalItem.MIN_TIER..CrystalItem.MAX_TIER).map { tier ->
        ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("mctraveler", "teleportation_crystal_tier_$tier"))
    }

    /** Puts every crystal recipe in [player]'s recipe book. */
    fun award(player: ServerPlayer) {
        player.awardRecipesByKey(KEYS)
    }
}
