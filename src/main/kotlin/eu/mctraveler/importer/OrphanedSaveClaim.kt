package eu.mctraveler.importer

import eu.mctraveler.persistence.PerWorldBucket
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
     * A quarantined save could not be claimed. Either way this needs an operator,
     * because once the player plays and gets a save of their own the guard will
     * (correctly) refuse the claim forever after.
     *
     * [anythingWritten] separates the two very different states an operator can
     * be in: false means the claim failed while working itself out, so the
     * quarantine is whole and a retry is free; true means it failed part-way
     * through writing, and what landed has to be looked at.
     */
    data class Failed(
        val username: String,
        val uuid: UUID,
        val reason: String,
        val anythingWritten: Boolean,
    ) : ClaimOutcome
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
    private val playerdata: Path,
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

        // Two phases, and the difference between their failures is the whole
        // reason they are separate: the claim is worked out in full before a byte
        // is written, so a save this server cannot place fails while the
        // quarantine is still whole — and a failure during the writes has to say
        // so, because an audit line claiming nothing was written when something
        // was is worse than no line at all.
        val prepared = try {
            prepare(uuid, offlineUuid, quarantined)
        } catch (failure: Exception) {
            return ClaimOutcome.Failed(username, uuid, reason(failure), anythingWritten = false)
        }
        return try {
            perform(uuid, offlineUuid, prepared)
            ClaimOutcome.Claimed(
                username = username,
                uuid = uuid,
                liveWorld = prepared.live.id,
                bucketWorld = prepared.bucket?.first?.id,
                dataVersion = prepared.dataVersion,
            )
        } catch (failure: Exception) {
            ClaimOutcome.Failed(username, uuid, reason(failure), anythingWritten = true)
        }
    }

    /** A claim worked out in full, with nothing written yet. */
    private class PreparedClaim(
        val quarantined: List<WorldTrio>,
        val live: WorldTrio,
        val liveSave: CompoundTag,
        val dataVersion: Int,
        val bucket: Pair<WorldTrio, PerWorldBucket>?,
    )

    private fun prepare(uuid: UUID, offlineUuid: UUID, quarantined: List<WorldTrio>): PreparedClaim {
        val live = liveWorld(uuid, quarantined)
        val other = quarantined.firstOrNull { it != live }
        val liveTag = read(quarantine.save(live.id, offlineUuid))
        return PreparedClaim(
            quarantined = quarantined,
            live = live,
            liveSave = PlayerdataImport.live(liveTag, live),
            dataVersion = NbtUtils.getDataVersion(liveTag, UNKNOWN_DATA_VERSION),
            bucket = other?.let { it to PlayerdataImport.bucket(read(quarantine.save(it.id, offlineUuid))) },
        )
    }

    private fun perform(uuid: UUID, offlineUuid: UUID, claim: PreparedClaim) {
        // Written in recovery order. Everything a repeated claim would simply
        // redo goes first; the live save goes last, because it is the file the
        // guard keys on and therefore the point of no return. Interrupted before
        // it, the next login claims again from an untouched quarantine;
        // interrupted after it, the next login is refused by the guard and the
        // leftover quarantine files are an operator's cleanup, not a data loss.
        claim.bucket?.let { (world, seeded) -> players.setBucket(uuid, world.id, seeded) }
        if (players.lastWorld(uuid) != claim.live.id) players.setLastWorld(uuid, claim.live.id)
        takeSidecar(quarantine.advancements(claim.live.id, offlineUuid), advancements.resolve("$uuid.json"))
        takeSidecar(quarantine.stats(claim.live.id, offlineUuid), stats.resolve("$uuid.json"))
        Files.createDirectories(playerdata)
        NbtIo.writeCompressed(claim.liveSave, playerdata.resolve("$uuid$SAVE_SUFFIX"))

        // The other World's advancements and statistics are dropped here, as
        // they are for every migrated player: two sets cannot merge into the one
        // shared set ADR 0001 keeps (spec deviation register 46).
        claim.quarantined.forEach { world ->
            quarantine.filesOf(world.id, offlineUuid).forEach(Files::deleteIfExists)
        }
    }

    private fun reason(failure: Exception): String = failure.message ?: failure.toString()

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
        Files.exists(playerdata.resolve("$uuid$SAVE_SUFFIX")) ||
            Files.exists(playerdata.resolve("$uuid$BACKUP_SUFFIX"))

    /** One per-player side file, moved out of the quarantine — never over an existing one. */
    private fun takeSidecar(from: Path, to: Path) {
        if (Files.notExists(from)) return
        if (Files.exists(to)) {
            throw IllegalStateException(
                "$to already exists for a player with no save of their own — refusing to overwrite it",
            )
        }
        Files.createDirectories(to.parent)
        Files.move(from, to)
    }

    private fun read(save: Path): CompoundTag = NbtIo.readCompressed(save, NbtAccounter.unlimitedHeap())

    companion object {
        /** What [ClaimOutcome.Claimed.dataVersion] reads when the save carries none at all. */
        const val UNKNOWN_DATA_VERSION = 0

        private const val SAVE_SUFFIX = ".dat"

        /** Vanilla's own backup of a player save, which `PlayerDataStorage.load` falls back to. */
        private const val BACKUP_SUFFIX = ".dat_old"
    }
}
