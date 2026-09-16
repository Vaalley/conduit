package eu.mctraveler.mixin;

import eu.mctraveler.store.EntityInvulnerabilityAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityInvulnerableAccessor extends EntityInvulnerabilityAccessor {
    @Accessor("invulnerable")
    @Override
    void mctraveler$setInvulnerable(boolean invulnerable);
}
