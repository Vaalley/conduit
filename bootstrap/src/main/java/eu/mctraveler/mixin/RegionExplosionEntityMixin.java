package eu.mctraveler.mixin;

import eu.mctraveler.bootstrap.Hooks;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Region protection for what an explosion's blast does to nearby entities —
 * narrower than {@link RegionExplosionMixin}'s block protection: a
 * non-member's wind charge is kept off a non-hostile mob or player inside a
 * region (unless it flies {@code WIND_CHARGES}), and a creeper's blast leaves
 * a name-tagged hostile alone (unless the region is {@code PUBLIC}). Wind
 * charges keep triggering blocks regardless of region — this touches only the
 * entity list {@code ServerExplosion.hurtEntities} works from, never the
 * block-triggering path.
 *
 * <p>Filtering the entity list before {@code hurtEntities} iterates it (rather
 * than injecting per-entity inside the loop, which the private method offers
 * no clean per-iteration seam for) covers both the damage and the knockback
 * that loop applies in one place.
 */
@Mixin(ServerExplosion.class)
public abstract class RegionExplosionEntityMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private Entity source;

    // this.level's declared field type is ServerLevel, so the call site
    // inside hurtEntities is emitted against ServerLevel, not the Level
    // interface it happens to override getEntities from.
    @Redirect(
            method = "hurtEntities",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/server/level/ServerLevel;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<Entity> mctraveler$filterProtectedEntities(ServerLevel level, Entity source, AABB box) {
        List<Entity> found = level.getEntities(source, box);
        found.removeIf(entity -> !Hooks.allowsExplosionEntityEffect(level, this.source, entity));
        return found;
    }
}
