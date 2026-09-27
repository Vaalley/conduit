package eu.mctraveler.gametest

import eu.mctraveler.crystal.CrystalItem
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.Items
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.BundleContents

/**
 * A crystal fits inside a bundle like any ordinary item plucked from a
 * 64-stack, not like a genuinely unstackable item (a shield, a totem) that
 * fills the whole thing by itself.
 *
 * [BundleContents]'s per-item weight is `1 / getMaxStackSize()` — the crystal
 * pins its own `MAX_STACK_SIZE` to 1 (spec User Story 22, so identical
 * crystals never merge into a stack a player could misread as one big
 * charge), which would otherwise make a single crystal weigh as much as an
 * entire bundle. [eu.mctraveler.mixin.CrystalBundleWeightMixin] overrides
 * just the weight calculation back to what an Echo Shard's real stack size
 * would give it. A mixin's effect only exists once Fabric Loader has woven
 * it in, so this has to be a gametest, not a plain unit test.
 */
class CrystalBundleGameTest {

    @GameTest
    fun sixtyFourCrystalsFillOneBundle(helper: GameTestHelper) {
        val bundle = BundleContents.Mutable()
        var inserted = 0
        repeat(64) {
            inserted += bundle.tryInsert(CrystalItem.of(1))
        }
        helper.assertValueEqual(inserted, 64, "crystals that fit in one bundle")
        helper.assertValueEqual(bundle.tryInsert(CrystalItem.of(1)), 0, "a 65th crystal fitting in a full bundle")
        helper.succeed()
    }

    @GameTest
    fun aCrystalWeighsTheSameAsAPlainEchoShard(helper: GameTestHelper) {
        val bundle = BundleContents.Mutable()
        bundle.tryInsert(CrystalItem.of(1))
        val crystalWeight = bundle.weight()

        val plain = BundleContents.Mutable()
        plain.tryInsert(ItemStack(Items.ECHO_SHARD))
        val echoShardWeight = plain.weight()

        helper.assertValueEqual(crystalWeight, echoShardWeight, "a crystal's bundle weight vs. a plain echo shard's")
        helper.succeed()
    }
}
