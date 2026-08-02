package eu.mctraveler.importer

import eu.mctraveler.persistence.PlayerStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils

/**
 * What the merge of Secondary into Primary did to a claimed save on the way in
 * (ticket 10; merge spec, User Stories 37, 38 and 40).
 *
 * The cases are kept apart, and named, because a claim is invisible to the
 * player and cannot be taken back: once its owner has played and has a save of
 * their own the claim is refused for good, so a save that landed in the wrong
 * place is diagnosable only from the line written the day it happened.
 */
sealed interface MergeOnClaim {

    /**
     * This server has never been merged, so there is nothing to apply. The state
     * of every server before the operation and of every server that never runs
     * it, and the state a save with no [MergeMarker] is in.
     */
    data object NotMerged : MergeOnClaim

    /**
     * The save came out of Secondary's quarantine, and every place it remembers
     * was moved by [offset] — the same move, by the same statement of it, that
     * the sweep made to the saves it could see on the night.
     */
    data class Relocated(val offset: MergeOffset) : MergeOnClaim

    /**
     * The save came out of Primary's quarantine on a merged server and was left
     * exactly as it was, because its owner was never anywhere that moved.
     */
    data object LeftWhereItWas : MergeOnClaim
}

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
        /** What the merge did to the save on the way in; see [MergeOnClaim]. */
        val merge: MergeOnClaim = MergeOnClaim.NotMerged,
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
 * **A claim on a merged server applies the merge on the way in.** This is the
 * whole live-code surface of that migration: everything else it does is offline
 * and happens once, but the quarantine is claimed lazily and some of it will be
 * claimed years after the landmass moved. So a save out of Secondary's
 * quarantine is put through the very transform the offline sweep ran, and
 * stamped with the very stamp the sweep wrote — [MergedPlayerdata] and
 * [MergeStamp], called rather than copied, because two statements of an offset
 * that can drift apart is the one failure this design exists to prevent. A save
 * out of Primary's quarantine is untouched: nothing of Primary's moved. Which of
 * the two happened is in the claim's log line, because a wrong landing years from
 * now has nothing else to be diagnosed from.
 *
 * **How far to move a save, and which of two saves to make live, are both asked
 * of the merge rather than of this server's present state** (ticket 14). The
 * offset comes out of the save's own [MergeMarker] — the file the merge wrote
 * about itself — and the World a returning player was last in comes out of the
 * merge stamp the sweep left on their record, because the sweep overwrote the
 * only other place that was written down. Both are questions about a night that
 * has already happened, and neither has an answer anybody has to remember to
 * supply.
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
    /**
     * Where [players] keeps its records. Two of the fields a claim writes are
     * ones the store does not model — the merge stamp, which it never did, and
     * the Per-World Bucket, which it stopped modelling when the Worlds were
     * retired — so a claim writes both into the record directly. That is exactly
     * what the sweep does with them, which is what lets both go through
     * [MergeStamp] and [PerWorldBuckets] rather than through second spellings.
     */
    private val records: Path,
    /**
     * The save's own record of the merge, which is where how far Secondary moved
     * comes from. There is no default: the one question this class must never
     * answer out of thin air is the one it would be answering if it could fall
     * back to something.
     */
    private val mergeMarker: MergeMarker,
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
        // was is worse than no line at all. A merge marker that cannot be read
        // fails here too, and that is exactly where it belongs: loudly, in the
        // log, with nothing written and the next login free to try again.
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
                merge = prepared.merge,
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
        /** What the merge did to [liveSave] on the way in. */
        val merge: MergeOnClaim,
        /** The World the player's own record ends up naming; see [prepare]. */
        val recordWorld: WorldTrio,
        /**
         * The World the sweep found on this record before it rewrote the field,
         * carried through so the stamp this claim writes keeps saying it; see
         * [MergeStamp.wasLastIn].
         */
        val wasLastIn: WorldTrio?,
    )

    private fun prepare(uuid: UUID, offlineUuid: UUID, quarantined: List<WorldTrio>): PreparedClaim {
        // Both of the merge's answers are read here, before a byte is written: a
        // marker that says the save was merged and cannot say by how much throws
        // out of this phase, where the failure costs nothing but a log line.
        val offset = mergeMarker.offsetApplied()
        val wasLastIn = MergeStamp.wasLastIn(records.resolve("$uuid$RECORD_SUFFIX"))
        val live = liveWorld(uuid, wasLastIn, quarantined)
        val other = quarantined.firstOrNull { it != live }
        val liveTag = read(quarantine.save(live.id, offlineUuid))
        val merge = mergeFor(live, offset)
        return PreparedClaim(
            quarantined = quarantined,
            live = live,
            liveSave = claimedSave(liveTag, live, merge),
            dataVersion = NbtUtils.getDataVersion(liveTag, UNKNOWN_DATA_VERSION),
            bucket = other?.let { it to claimedBucket(read(quarantine.save(it.id, offlineUuid)), it, offset) },
            merge = merge,
            // A merged server has one World, so a claim that applied the merge
            // records Primary rather than the quarantine it read out of — the
            // same rewrite the sweep made to every record it swept, and for the
            // same reason: a record still naming Secondary would have the login
            // path place its owner in a World that is being retired.
            recordWorld = if (merge is MergeOnClaim.Relocated) WorldLayout.PRIMARY else live,
            wasLastIn = wasLastIn,
        )
    }

    /**
     * What a merge that moved Secondary by [offset] does to a save out of
     * [world]'s quarantine, or [MergeOnClaim.NotMerged] when there was no merge.
     *
     * The asymmetry is the substance of it: Secondary's landmass moved and
     * Primary's did not, so the quarantine directory a save was sitting in is
     * what decides whether it is transformed. The save cannot be asked — a
     * Portal-era backend wrote it, and both backends were plain vanilla servers
     * naming the vanilla trio whichever World they became.
     */
    private fun mergeFor(world: WorldTrio, offset: MergeOffset?): MergeOnClaim {
        if (offset == null) return MergeOnClaim.NotMerged
        return if (world === WorldLayout.SECONDARY) {
            MergeOnClaim.Relocated(offset)
        } else {
            MergeOnClaim.LeftWhereItWas
        }
    }

    /**
     * The quarantined save [tag] as this server's live playerdata, for a player
     * whose last World was [world].
     *
     * Before the merge that is [PlayerdataImport]'s re-pointing and nothing more.
     * After it, a save out of Secondary's quarantine is re-pointed at **Primary**
     * first and then moved: the re-pointing leaves the overworld's and the
     * nether's ids exactly as they were and still gives a save naming a dimension
     * this server has never heard of the overworld landing it has always had, and
     * the move then puts every place it remembers where that place is on today's
     * map. Composed that way round, the two steps say "the same ground, at its
     * new coordinates" rather than routing anyone through a World that is going
     * away.
     */
    private fun claimedSave(tag: CompoundTag, world: WorldTrio, merge: MergeOnClaim): CompoundTag =
        when (merge) {
            is MergeOnClaim.Relocated -> MergedPlayerdata.mergedFromSecondarysQuarantine(
                PlayerdataImport.live(tag, WorldLayout.PRIMARY),
                merge.offset,
            )
            else -> PlayerdataImport.live(tag, world)
        }

    /**
     * The other World's save read as that World's Per-World Bucket — moved too,
     * when that World is Secondary and this server has been merged.
     *
     * The banked half takes the transform for the same reason the live half does,
     * and which half that is depends on the player: a returning player
     * quarantined on both sides has whichever save they were last in made live
     * and the other one banked, so either half can be the one that moved. Both
     * mirrors are this one line, as they are in the sweep.
     */
    private fun claimedBucket(tag: CompoundTag, world: WorldTrio, offset: MergeOffset?): PerWorldBucket {
        val bucket = PlayerdataImport.bucket(tag)
        return if (offset != null && world === WorldLayout.SECONDARY) {
            MergedPlayerdata.merged(bucket, offset)
        } else {
            bucket
        }
    }

    private fun perform(uuid: UUID, offlineUuid: UUID, claim: PreparedClaim) {
        // Written in recovery order. Everything a repeated claim would simply
        // redo goes first; the live save goes last, because it is the file the
        // guard keys on and therefore the point of no return. Interrupted before
        // it, the next login claims again from an untouched quarantine;
        // interrupted after it, the next login is refused by the guard and the
        // leftover quarantine files are an operator's cleanup, not a data loss.
        claim.bucket?.let { (world, seeded) ->
            PerWorldBuckets.into(records.resolve("$uuid$RECORD_SUFFIX"), world.id, seeded)
        }
        if (players.lastWorld(uuid) != claim.recordWorld.id) players.setLastWorld(uuid, claim.recordWorld.id)
        stamp(uuid, claim)
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

    /**
     * The merge stamp, on the record of a player the merge actually moved.
     *
     * A claim that moved nothing writes none, exactly as the sweep left an
     * unmoved player's record alone: the stamp answers "was this player's
     * geography rewritten, and by how much?", and one on a Primary claim would
     * answer it wrongly.
     *
     * The instant is this claim's own rather than the merge's, which is the only
     * part of the stamp that differs from the sweep's and the only part that
     * should. It records when *this* record was moved, so a save handed back
     * years after the night reads as exactly that instead of being backdated into
     * the crowd — and the offset beside it is the same offset, which is what the
     * question is really about.
     *
     * The pre-merge World the sweep recorded is written back out unchanged. This
     * claim consumed the quarantine and nothing will ask the question again, but
     * overwriting the stamp would quietly delete the record's own account of what
     * the merge found here — and that account is the only evidence of why this
     * player was handed the save they were handed.
     */
    private fun stamp(uuid: UUID, claim: PreparedClaim) {
        val merge = claim.merge
        if (merge !is MergeOnClaim.Relocated) return
        MergeStamp.into(records.resolve("$uuid$RECORD_SUFFIX"), merge.offset, Instant.now(), claim.wasLastIn)
    }

    private fun reason(failure: Exception): String = failure.message ?: failure.toString()

    /**
     * Which World's save becomes the live one, by the migration's own rule: the
     * World the player was last in if it is one they have a quarantined save in,
     * otherwise the World they do have one in (spec deviation register 50). An
     * unrecognised World counts as no answer, and no answer at all means Primary.
     *
     * [wasLastIn] — the World the merge stamp says this record named before the
     * sweep — is preferred over the record's live `lastServer` because on a
     * merged server that field no longer answers this question: the sweep
     * rewrote it to Primary for everyone, correctly, since there is one World
     * afterwards. Reading it here would hand a returning player who was last in
     * Secondary their *Primary* save, and the mistake is nearly invisible —
     * their coordinates are right either way, and only the inventory, XP and
     * advancements are the wrong ones (ticket 14). A record with no stamp was
     * never swept and still answers for itself, which is why the fallback is the
     * behaviour this had before the merge existed rather than a second guess.
     */
    private fun liveWorld(uuid: UUID, wasLastIn: WorldTrio?, quarantined: List<WorldTrio>): WorldTrio {
        val recorded = wasLastIn ?: players.lastWorld(uuid)?.let(WorldLayout::byId) ?: WorldLayout.PRIMARY
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

        /** One player record, as [eu.mctraveler.persistence.JsonPlayerStore] names it. */
        private const val RECORD_SUFFIX = ".json"

        /** Vanilla's own backup of a player save, which `PlayerDataStorage.load` falls back to. */
        private const val BACKUP_SUFFIX = ".dat_old"
    }
}
