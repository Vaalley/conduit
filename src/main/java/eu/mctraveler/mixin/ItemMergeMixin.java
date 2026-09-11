package eu.mctraveler.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Widens the item-merge scan radius, the same idea Paper ships as
 * {@code item-merge-radius} — and the same approach {@link ExperienceOrbMixin}
 * already takes for XP orbs on this server.
 *
 * <p>Vanilla's {@code ItemEntity.mergeWithNeighbours} looks for merge
 * candidates inside {@code getBoundingBox().inflate(0.5, 0, 0.5)} — a dropped
 * item only ever merges with one practically on top of it. On farms, dropper
 * lines and death-scatter that leaves dozens of item entities ticking physics
 * where a handful would do; widening the scan to 2.5 blocks lets them clump
 * into far fewer entities, and every merged entity is one fewer {@code tick()}
 * paying fluid, movement and block checks.
 *
 * <p>Only the search radius changes: the merge predicate itself (alive, no
 * infinite pickup delay, not despawned, stack below max size, stackable) is
 * untouched, so no item is ever merged that vanilla would not have merged had
 * it drifted closer.
 */
@Mixin(ItemEntity.class)
public abstract class ItemMergeMixin {

    /** The horizontal (and only) half-extent of the merge scan box. */
    private static final double MERGE_RADIUS = 2.5;

    /**
     * In {@code ItemEntity.mergeWithNeighbours} — widens both horizontal
     * halves of {@code inflate(0.5, 0, 0.5)}. The y half stays 0 as in vanilla.
     */
    @ModifyConstant(
        method = "mergeWithNeighbours",
        constant = @Constant(doubleValue = 0.5),
        expect = 2
    )
    private double mctraveler$widerMergeScan(double radius) {
        return MERGE_RADIUS;
    }
}
