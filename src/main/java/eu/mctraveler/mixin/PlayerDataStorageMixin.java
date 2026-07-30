package eu.mctraveler.mixin;

import eu.mctraveler.importer.OrphanedSaveClaimFeature;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives a joining player their quarantined Portal save back, in the one instant
 * that can work: after their username is known and before vanilla reads their
 * save file (ticket 20).
 *
 * <p>{@code load(NameAndId)} is where every path that wants a player's save ends
 * up — the configuration phase's {@code PrepareSpawnTask}, which decides the
 * dimension and position the player will spawn at, and the spawn that follows it.
 * Claiming here means the file is simply <em>there</em> when vanilla looks, so the
 * login stays entirely ordinary: no teleport, no second load, nothing the player
 * can see. Vanilla applies its own data fixers to whatever it then reads, which is
 * what lets a Portal-era (1.21.10) save be claimed long after the level itself was
 * upgraded.
 *
 * <p>A pure shim, per ADR 0002: the decision, the never-overwrite-a-live-player
 * guard, and the file work are all in
 * {@link eu.mctraveler.importer.OrphanedSaveClaim}, and the whole thing is inert —
 * one directory check — on a server with no quarantine, which is every server that
 * did not migrate.
 */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageMixin {

    @Inject(method = "load(Lnet/minecraft/server/players/NameAndId;)Ljava/util/Optional;", at = @At("HEAD"))
    private void mctraveler$claimOrphanedSave(
        NameAndId who,
        CallbackInfoReturnable<Optional<CompoundTag>> cir
    ) {
        OrphanedSaveClaimFeature.claimBefore(who);
    }
}
