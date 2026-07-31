package eu.mctraveler.crystal

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import eu.mctraveler.MinecraftTestBootstrap
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapelessRecipe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The three crystal recipes ship as datapack JSON (spec User Story 20,
 * deviation 7), which puts the crystal's component set in two places at once:
 * [CrystalItem.of] and the `result` block of each recipe file. This test is the
 * seam between them — it decodes the shipped JSON exactly as the server's
 * datapack reload does, and holds the crafted item to the builder's.
 *
 * Without it, a lore tweak in one place and not the other would ship a crystal
 * that the mod itself no longer recognises.
 */
class CrystalRecipeJsonTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() = MinecraftTestBootstrap.ensure()
    }

    private val ops: RegistryOps<com.google.gson.JsonElement> =
        RegistryOps.create(JsonOps.INSTANCE, VanillaRegistries.createLookup())

    private fun recipeFile(tier: Int): Path =
        Path.of("src/main/resources/data/mctraveler/recipe/teleportation_crystal_tier_$tier.json")

    private fun json(tier: Int) = JsonParser.parseString(Files.readString(recipeFile(tier)))

    private fun resultOf(tier: Int): ItemStack =
        ItemStackTemplate.CODEC
            .parse(ops, json(tier).asJsonObject.get("result"))
            .getOrThrow { message -> AssertionError("tier $tier result did not decode: $message") }
            .create()

    @Test
    fun `every tier's recipe decodes as the server's datapack reload would`() {
        for (tier in 1..3) {
            val recipe = Recipe.CODEC.parse(ops, json(tier))
                .getOrThrow { message -> AssertionError("tier $tier recipe did not decode: $message") }
            if (tier == 1) {
                assertInstanceOf(ShapelessRecipe::class.java, recipe, "tier 1 is shapeless")
            } else {
                assertInstanceOf(ShapedRecipe::class.java, recipe, "tier $tier is shaped")
            }
        }
    }

    @Test
    fun `what each recipe crafts is exactly what the item builder builds`() {
        for (tier in 1..3) {
            val crafted = resultOf(tier)
            assertTrue(
                ItemStack.matches(crafted, CrystalItem.of(tier)),
                "tier $tier recipe result drifted from CrystalItem.of($tier):\n" +
                    "  recipe:  ${crafted.componentsPatch}\n" +
                    "  builder: ${CrystalItem.of(tier).componentsPatch}",
            )
        }
    }

    @Test
    fun `a crafted crystal is recognised as one, at its own tier`() {
        for (tier in 1..3) {
            val crafted = resultOf(tier)
            assertTrue(CrystalItem.isCrystal(crafted), "tier $tier recipe result is not a crystal")
            assertEquals(tier, CrystalItem.tierOf(crafted), "tier $tier recipe result tier")
        }
    }
}
