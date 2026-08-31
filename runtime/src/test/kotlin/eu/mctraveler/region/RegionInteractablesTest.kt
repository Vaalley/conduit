package eu.mctraveler.region

import eu.mctraveler.MinecraftTestBootstrap
import eu.mctraveler.region.RegionInteractables.BlockUse
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class RegionInteractablesTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() = MinecraftTestBootstrap.ensure()

        private fun classify(block: net.minecraft.world.level.block.Block) =
            RegionInteractables.classify(block.defaultBlockState())
    }

    @Test
    fun `workstations and read-only blocks are FREE`() {
        for (block in listOf(
            Blocks.CRAFTING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.SMITHING_TABLE,
            Blocks.GRINDSTONE, Blocks.LOOM, Blocks.ENCHANTING_TABLE, Blocks.BELL,
        )) {
            assertEquals(BlockUse.FREE, classify(block), "$block should be FREE")
        }
    }

    @Test
    fun `furnace family and view-only containers are CONTAINER_VIEW`() {
        for (block in listOf(
            Blocks.FURNACE, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.DISPENSER,
            Blocks.DROPPER, Blocks.BREWING_STAND, Blocks.CRAFTER, Blocks.BEACON,
            Blocks.LECTERN,
        )) {
            assertEquals(BlockUse.CONTAINER_VIEW, classify(block), "$block should be CONTAINER_VIEW")
        }
    }

    @Test
    fun `region-changing blocks require membership`() {
        for (block in listOf(
            Blocks.COMPOSTER, Blocks.WATER_CAULDRON, Blocks.CAULDRON, Blocks.LAVA_CAULDRON,
            Blocks.CHISELED_BOOKSHELF, Blocks.DECORATED_POT, Blocks.JUKEBOX,
            Blocks.DAYLIGHT_DETECTOR, Blocks.NOTE_BLOCK, Blocks.ANVIL, Blocks.CHIPPED_ANVIL,
            Blocks.FLOWER_POT, Blocks.POTTED_CACTUS, Blocks.BEE_NEST, Blocks.BEEHIVE,
            Blocks.OAK_SHELF, Blocks.DRAGON_EGG, Blocks.RESPAWN_ANCHOR, Blocks.OAK_SIGN,
        )) {
            assertEquals(BlockUse.REQUIRES_MEMBERSHIP, classify(block), "$block should require membership")
        }
    }

    @Test
    fun `campfire is its own class`() {
        assertEquals(BlockUse.CAMPFIRE, classify(Blocks.CAMPFIRE))
        assertEquals(BlockUse.CAMPFIRE, classify(Blocks.SOUL_CAMPFIRE))
    }

    @Test
    fun `an unclassified block is FREE`() {
        assertEquals(BlockUse.FREE, classify(Blocks.STONE))
        assertEquals(BlockUse.FREE, classify(Blocks.CHEST))
    }

    @Test
    fun `region-modifying items are the ones that place or change a block`() {
        for (item in listOf(
            Items.STONE, Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.COD_BUCKET,
            Items.BONE_MEAL, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE, Items.DIAMOND_HOE,
            Items.DIAMOND_SHOVEL, Items.DIAMOND_AXE, Items.SHEARS, Items.ARMOR_STAND,
            Items.ITEM_FRAME, Items.PAINTING, Items.END_CRYSTAL, Items.ZOMBIE_SPAWN_EGG,
            Items.LEAD, Items.HONEYCOMB,
        )) {
            assertTrue(RegionInteractables.isRegionModifyingItem(ItemStack(item)), "$item should be region-modifying")
        }
    }

    @Test
    fun `harmless items are not region-modifying`() {
        for (item in listOf(
            Items.AIR, Items.DIAMOND_SWORD, Items.SPYGLASS, Items.MAP, Items.COMPASS,
            Items.CLOCK, Items.STICK, Items.BOOK, Items.GOAT_HORN, Items.FIREWORK_ROCKET,
            Items.APPLE, Items.BOW, Items.FISHING_ROD,
        )) {
            assertFalse(RegionInteractables.isRegionModifyingItem(ItemStack(item)), "$item should be harmless")
        }
    }

    @Test
    fun `a campfire takes food but not a shovel or a water bottle`() {
        assertTrue(RegionInteractables.isCampfireFood(ItemStack(Items.PORKCHOP)))
        assertFalse(RegionInteractables.changesCampfire(ItemStack(Items.PORKCHOP)))
        assertTrue(RegionInteractables.changesCampfire(ItemStack(Items.DIAMOND_SHOVEL)))
        assertTrue(RegionInteractables.changesCampfire(ItemStack(Items.FLINT_AND_STEEL)))
        assertFalse(RegionInteractables.changesCampfire(ItemStack(Items.STICK)))
    }
}
