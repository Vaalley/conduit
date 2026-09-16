package eu.mctraveler.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityInvulnerableAccessor {
    @Accessor("invulnerable")
    void mctraveler$setInvulnerable(boolean invulnerable);
}
