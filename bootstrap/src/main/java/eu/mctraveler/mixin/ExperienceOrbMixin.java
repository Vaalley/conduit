package eu.mctraveler.mixin;

import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * The Portal's orb-merge effect, server-side (spec User Story 45; deviation register 12).
 *
 * <p>Vanilla already merges experience orbs losslessly — {@code ExperienceOrb.award}
 * folds a spawn into a nearby same-value orb by bumping its {@code count}, and ticking
 * orbs periodically re-merge — but it only considers candidates whose entity ids fall
 * in the same "id mod 40" bucket (randomly probed at spawn). That leaves grinder bursts
 * as dozens of orb entities and lets sustained grinding grow the swarm without bound
 * (measured in {@code XpOrbMergeGameTest}: 100 same-tick awards left 38 orbs; 200 left 68).
 *
 * <p>This mixin shrinks that bucket modulus to 1 — every entity id lands in the same
 * bucket — so any same-value orb in vanilla's merge range qualifies and a burst
 * collapses to one orb entity per value class. Everything else in vanilla's merge
 * predicate (the not-removed check, value equality, and whatever a future version adds)
 * stays in force, and XP cannot be lost by construction: merging still goes through
 * vanilla's own count-stacking, which this mixin does not touch.
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {

    /**
     * In {@code ExperienceOrb.canMerge(ExperienceOrb, int, int)} — the predicate behind
     * both spawn-time merging and the periodic merge scan — turns the bucket condition
     * {@code (orb.getId() - id) % 40 == 0} into {@code ... % 1 == 0}, which always holds.
     */
    @ModifyConstant(
        method = "canMerge(Lnet/minecraft/world/entity/ExperienceOrb;II)Z",
        constant = @Constant(intValue = 40)
    )
    private static int mctraveler$mergeAcrossIdBuckets(int bucketModulus) {
        return 1;
    }
}
