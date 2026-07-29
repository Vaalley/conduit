package eu.mctraveler.importer

import eu.mctraveler.persistence.PlayerStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils

/** What one login-time claim did — the whole of what an audit needs. */
sealed interface ClaimOutcome {

    /**
     * Nothing is quarantined under this player's name. The overwhelmingly common
     * case, and the only one on a server that never migrated.
     */
    data object NoOrphan : ClaimOutcome

    /**
     * A quarantined save is keyed to this username, but the player already has a
     * save of their own — so the claim was skipped and the orphan left untouched.
     * See [OrphanedSaveClaim].
     */
    data class AlreadyLive(val username: String, val uuid: UUID) : ClaimOutcome

    /**
     * The save was claimed: [liveWorld]'s save is now the player's live
     * playerdata, [bucketWorld]'s (if they had one) seeded that World's
     * Per-World Bucket, and [dataVersion] is what the claimed save was stamped
     * with before vanilla's data fixers see it.
     */
    data class Claimed(
        val username: String,
        val uuid: UUID,
        val liveWorld: String,
        val bucketWorld: String?,
        val dataVersion: Int,
    ) : ClaimOutcome

    /**
     * A quarantined save could not be claimed. Nothing was written, and the
     * quarantine is intact — but this needs an operator, because once the player
     * plays and gets a save of their own the guard will (correctly) refuse
     * forever after.
     */
    data class Failed(val username: String, val uuid: UUID, val reason: String) : ClaimOutcome
}

/**
 * Claiming an orphaned save at login (ticket 20) — the per-player half of the
 * migration, performed later, when the one thing that can unlock an
 * offline-keyed save is finally known: the owner's username.
 *
 * A migration quarantines the saves it could not name (see [SaveQuarantine]).
 * This runs just before vanilla reads a joining player's own save, hashes their
 * username the way the offline-mode backends did, and — if the quarantine holds
 * saves under that hash — performs exactly what the migration would have done
 * for them: the save from their last World becomes their live playerdata under
 * their Mojang uuid, the other World's save seeds that World's Per-World Bucket,
 * and the live World's advancements and statistics come across. The transforms
 * are [PlayerdataImport]'s, unchanged; only the timing is different.
 *
 * **A player who already has a save is never touched.** That is the one rule
 * everything else bends around. An offline uuid is a hash of a *username*, and
 * usernames can be released and re-registered, so the hash is evidence of who a
 * save probably belongs to, never proof. Overwriting a live save could therefore
 * hand one player another player's inventory — and even in the ordinary case it
 * would replace a player's current state with a years-old one. So the presence
 * of any save vanilla would load (`<uuid>.dat`, or the `<uuid>.dat_old` backup
 * it falls back to) ends the claim, and the orphan is left where it is.
 *
 * Quarantined saves are Portal-era (1.21.10) data. That is deliberately fine:
 * `PlayerDataStorage.load` runs `DataFixTypes.PLAYER.updateToCurrentVersion` on
 * whatever it reads, so a save claimed long after the level itself was upgraded
 * is still upgraded on the way in, and rewritten at the current version the next
 * time the player is saved. The version each claim carried in is reported in
 * [ClaimOutcome.Claimed.dataVersion] rather than left to be guessed at.
 *
 * All paths come in from the caller so this is testable with no server at all;
 * [OrphanedSaveClaimFeature] supplies the live server's.
 */
class OrphanedSaveClaim(
    private val quarantine: SaveQuarantine,
    /** Vanilla's own player-save directory — where `PlayerDataStorage` will look. */
    private val saves: Path,
    private val advancements: Path,
    private val stats: Path,
    private val players: PlayerStore,
) {

    /**
     * Claims whatever [username] unlocks, for the player joining as [uuid].
     *
     * Cheap in the normal case: an absent quarantine costs one directory check
     * and nothing else, which is the state of every server that never migrated.
     */
    fun claim(uuid: UUID, username: String): ClaimOutcome {
        if (!quarantine.isPresent) return ClaimOutcome.NoOrphan
        // Case-sensitive, exactly as the backends hashed the name the player
        // logged in with.
        val offlineUuid = OfflineUuid.of(username)
        val quarantined = WorldLayout.all.filter { Files.isRegularFile(quarantine.save(it.id, offlineUuid)) }
        if (quarantined.isEmpty()) return ClaimOutcome.NoOrphan
        if (hasSave(uuid)) return ClaimOutcome.AlreadyLive(username, uuid)
        return try {
            perform(uuid, username, offlineUuid, quarantined)
        } catch (failure: Exception) {
            ClaimOutcome.Failed(username, uuid, failure.message ?: failure.toString())
        }
    }

    private fun perform(
        uuid: UUID,
        username: String,
        offlineUuid: UUID,
        quarantined: List<WorldTrio>,
    ): ClaimOutcome {
        val live = liveWorld(uuid, quarantined)
        val other = quarantined.firstOrNull { it != live }

        // Read and transform everything before a byte is written: a save this
        // server cannot place must fail while the quarantine is still whole.
        val liveTag = read(quarantine.save(live.id, offlineUuid))
        val liveSave = PlayerdataImport.live(liveTag, live)
        val bucket = other?.let { it to PlayerdataImport.bucket(read(quarantine.save(it.id, offlineUuid))) }

        // Written in recovery order. Everything a repeated claim would simply
        // redo goes first; the live save goes last, because it is the file the
        // guard keys on and therefore the point of no return. Interrupted before
        // it, the next login claims again from an untouched quarantine;
        // interrupted after it, the next login is refused by the guard and the
        // leftover quarantine files are an operator's cleanup, not a data loss.
        bucket?.let { (world, seeded) -> players.setBucket(uuid, world.id, seeded) }
        if (players.lastWorld(uuid) != live.id) players.setLastWorld(uuid, live.id)
        takeSidecar(quarantine.advancements(live.id, offlineUuid), advancements.resolve("$uuid.json"))
        takeSidecar(quarantine.stats(live.id, offlineUuid), stats.resolve("$uuid.json"))
        Files.createDirectories(saves)
        NbtIo.writeCompressed(liveSave, saves.resolve("$uuid$SAVE_SUFFIX"))

        // The other World's advancements and statistics are dropped here, as
        // they are for every migrated player: two sets cannot merge into the one
        // shared set ADR 0001 keeps (spec deviation register 46).
        quarantined.forEach { world -> quarantine.filesOf(world.id, offlineUuid).forEach(Files::deleteIfExists) }

        return ClaimOutcome.Claimed(
            username = username,
            uuid = uuid,
            liveWorld = live.id,
            bucketWorld = bucket?.first?.id,
            dataVersion = NbtUtils.getDataVersion(liveTag, 0),
        )
    }

    /**
     * Which World's save becomes the live one, by the migration's own rule: the
     * Portal record's `lastServer` if it names a World with a quarantined save,
     * otherwise the World they do have one in (spec deviation register 50). An
     * unrecognised `lastServer` counts as no answer.
     */
    private fun liveWorld(uuid: UUID, quarantined: List<WorldTrio>): WorldTrio {
        val recorded = players.lastWorld(uuid)?.let(WorldLayout::byId) ?: WorldLayout.PRIMARY
        return quarantined.firstOrNull { it == recorded } ?: quarantined.first()
    }

    /**
     * Whether this server already holds a save for [uuid] — the guard. Both
     * spellings count: `PlayerDataStorage.load` falls back to `<uuid>.dat_old`,
     * so a player whose save survives only as vanilla's backup is still a live
     * player whose data must not be replaced.
     */
    private fun hasSave(uuid: UUID): Boolean =
        Files.exists(saves.resolve("$uuid$SAVE_SUFFIX")) || Files.exists(saves.resolve("$uuid$BACKUP_SUFFIX"))

    /** One per-player side file, moved out of the quarantine — never over an existing one. */
    private fun takeSidecar(from: Path, to: Path) {
        if (Files.notExists(from)) return
        require(Files.notExists(to)) {
            "$to already exists for a player with no save of their own — refusing to overwrite it"
        }
        Files.createDirectories(to.parent)
        Files.move(from, to)
    }

    private fun read(save: Path): CompoundTag = NbtIo.readCompressed(save, NbtAccounter.unlimitedHeap())

    private companion object {
        const val SAVE_SUFFIX = ".dat"

        /** Vanilla's own backup of a player save, which `PlayerDataStorage.load` falls back to. */
        const val BACKUP_SUFFIX = ".dat_old"
    }
}
