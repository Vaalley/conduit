package eu.mctraveler.importer

import eu.mctraveler.persistence.JsonPlayerStore
import eu.mctraveler.persistence.PerWorldBucket
import eu.mctraveler.persistence.PortalJson
import eu.mctraveler.worlds.BankedPositions
import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.name
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo

/**
 * The stamp a record the merge touched carries afterwards (ticket 06): that the
 * merge happened, when, and with which offset.
 *
 * It exists so that "was this player swept?" is answerable from the record months
 * later rather than guessed at, and it is a shared value rather than a string
 * built twice because the claim path (ticket 10) has to stamp a save it takes out
 * of the Portal quarantine *identically* — a returning player years from now must
 * be indistinguishable from one who was here on the night.
 */
object MergeStamp {

    /** The record field the stamp lives in, beside the Portal's own legacy fields. */
    const val FIELD = "merge"

    /** The stamp's raw JSON value, ready to be a [PortalJson.Field]'s value slice. */
    fun json(offset: MergeOffset, at: Instant): String =
        """{"at":${PortalJson.encodeString(at.toString())},""" +
            """"offset":{"x":${offset.x},"z":${offset.z}}}"""

    /**
     * Adds the stamp to the player record at [record], leaving every other
     * field's bytes exactly as they were.
     *
     * Both the ones who stamp go through here — the sweep, on a staged record it
     * is about to commit, and [OrphanedSaveClaim], on a live record years later —
     * so a returning player's stamp is the same field in the same shape as one
     * written on the night rather than a second spelling of it.
     */
    fun into(record: Path, offset: MergeOffset, at: Instant) {
        val fields = PortalJson.parse(Files.readString(record))
        fields[FIELD] = PortalJson.Field(PortalJson.encodeString(FIELD), json(offset, at))
        Files.writeString(record, PortalJson.emit(fields.values))
    }
}

/**
 * Where a player's other base ended up, in the merged map's own coordinates.
 *
 * The Per-World Bucket for the World a player was *not* in is being discarded —
 * there is only one World now, and nothing is restored to anyone — but the place
 * it names is a real base with real chests in it, and after a cold merge the
 * player has no way to find it. So each one is recorded here for `/switch` to
 * read back and tell them (merge spec, "Data sweep"; ticket 08).
 *
 * [world] is the World the base *was* in, which is what the player recognises;
 * [dimension] and the coordinates are where it is now.
 */
data class BankedPosition(
    val uuid: UUID,
    val world: String,
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

/** What the player sweep did, for the operator to check against what they expected. */
data class PlayerSweepReport(
    /** Players something of whose was actually rewritten. */
    val swept: Int,
    /** Players the merge read and had nothing to change for. */
    val leftAlone: Int,
    val banked: List<BankedPosition>,
    /**
     * Players whose save leaves them standing in Secondary's End, which the merge
     * discards. The sweep moves nothing of theirs — there is no offset for a
     * dimension that is being deleted — and names them so that the End gate
     * (ticket 07) can state each one's landing before the operator accepts the
     * loss.
     */
    val anchoredInSecondaryEnd: List<UUID>,
) : MergeSection {
    override fun lines(): List<String> = listOf(
        reportLine("players swept", "$swept"),
        reportLine("players left alone", "$leftAlone"),
        reportLine("banked positions", "${banked.size}"),
    ) + if (anchoredInSecondaryEnd.isEmpty()) {
        emptyList()
    } else {
        listOf(
            reportLine(
                "left in Secondary's End",
                "${anchoredInSecondaryEnd.size} — not moved; the End is discarded, not relocated",
            ),
        )
    }
}

/**
 * The player half of the data sweep (merge spec, "Data sweep"; ticket 06): every
 * player's save and every player's record, rewritten so that the places they
 * remember in Secondary name where those places are now.
 *
 * A player who was last in Secondary logs in standing exactly where they logged
 * out, in the corresponding Primary dimension, facing the same way, with their
 * bed, their death location, their vehicle and their compasses all still pointing
 * at the right things. A player who was last in Primary is not moved — and
 * neither is anything of theirs that was already in Primary, which is not the
 * same claim: their ender chest can hold a compass bound to a Secondary
 * lodestone, and that one moves. [MergedPlayerdata] is where that distinction is
 * made and why it is one code path for both mirrors.
 *
 * Nothing is written outside [MergeStaging], and nothing at all is staged for a
 * player the merge had no work to do for — so "left alone" means the file was
 * never opened for writing, not that it was rewritten to the same value. That is
 * what keeps the guarantee that every other field in a player record, legacy ones
 * included, passes through byte for byte.
 */
class PlayerSweep(private val plan: MergePlan, private val offset: MergeOffset) {

    private val playerdataDir: Path = plan.targetDir.resolve(plan.levelName).resolve(PLAYERDATA_DIRECTORY)
    private val recordsDir: Path = plan.targetDir.resolve(RECORDS_DIRECTORY)

    /** When this merge ran, fixed once so every record it stamps agrees to the second. */
    private val at: Instant = Instant.now()

    fun sweep(staging: MergeStaging): PlayerSweepReport {
        var swept = 0
        var leftAlone = 0
        val banked = mutableListOf<BankedPosition>()
        val inTheEnd = mutableListOf<UUID>()

        for (uuid in players()) {
            try {
                // Both halves always run: a player's save and their record can
                // need moving independently of each other.
                val save = sweepSave(uuid, staging, inTheEnd)
                val record = sweepRecord(uuid, staging, banked)
                if (save || record) swept++ else leftAlone++
            } catch (failure: Exception) {
                throw IllegalStateException("could not sweep player $uuid: ${failure.message}", failure)
            }
        }

        // A merge that banked nothing writes no artifact rather than an empty
        // one: `/switch` has to cope with there being no file at all anyway —
        // every server that never merged is in that state — so the two cases are
        // the same case, and the smaller footprint is the honest one.
        if (banked.isNotEmpty()) writeBankedPositions(staging, banked)
        return PlayerSweepReport(swept, leftAlone, banked, inTheEnd)
    }

    /**
     * Every player the run directory knows of, from both sides: a save with no
     * record belongs to someone who joined after the Portal, and a record with no
     * save to someone whose save is still in the cutover's quarantine or who never
     * played since. Sorted, so the report and the artifact read the same on the
     * rehearsal and on the night.
     */
    private fun players(): List<UUID> {
        val found = sortedSetOf<UUID>(compareBy(UUID::toString))
        found += namesIn(playerdataDir, SAVE_SUFFIXES)
        found += namesIn(recordsDir, listOf(RECORD_SUFFIX))
        return found.toList()
    }

    /**
     * The uuids named by the files in [directory] carrying one of [suffixes].
     *
     * A name that is not exactly a uuid is not a player: the live deployment's
     * `playerdata/` held 93 files named `<uuid>-<digits>.dat` that nothing can key
     * to anybody (see [PortalImport]), and they are left exactly where they are
     * rather than swept into a coordinate space they may not belong to.
     */
    private fun namesIn(directory: Path, suffixes: List<String>): List<UUID> {
        if (Files.notExists(directory)) return emptyList()
        return Files.list(directory).use { entries ->
            entries.toList()
                .mapNotNull { file ->
                    val suffix = suffixes.firstOrNull { file.name.endsWith(it) } ?: return@mapNotNull null
                    runCatching { UUID.fromString(file.name.removeSuffix(suffix)) }.getOrNull()
                }
        }
    }

    // ---- the save -----------------------------------------------------------

    /**
     * [uuid]'s playerdata, and vanilla's own backup of it.
     *
     * The backup counts because `PlayerDataStorage.load` falls back to it when the
     * live save will not read: an unswept `.dat_old` would land a player at
     * Secondary's old coordinates on exactly the day their save went bad, which is
     * the worst possible day for it.
     */
    private fun sweepSave(uuid: UUID, staging: MergeStaging, inTheEnd: MutableList<UUID>): Boolean {
        var touched = false
        for (suffix in SAVE_SUFFIXES) {
            val file = playerdataDir.resolve("$uuid$suffix")
            if (Files.notExists(file)) continue
            val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            if (suffix == LIVE_SAVE_SUFFIX && isInSecondarysEnd(tag)) inTheEnd += uuid
            val merged = MergedPlayerdata.merged(tag, offset)
            if (merged == tag) continue
            NbtIo.writeCompressed(merged, staging.replacing(file))
            touched = true
        }
        return touched
    }

    private fun isInSecondarysEnd(tag: CompoundTag): Boolean =
        tag.getStringOr("Dimension", "") == WorldLayout.SECONDARY.dimensionId(DimensionRole.END)

    // ---- the record ---------------------------------------------------------

    /**
     * [uuid]'s player record: the World they were last in, their Secondary
     * Per-World Bucket, and — if anything of it changed — the merge stamp.
     *
     * The record is copied into staging and edited there through the very
     * [JsonPlayerStore] the live server uses, so the guarantee that unknown fields
     * survive read-modify-write byte-for-byte is the store's own, already proven,
     * rather than a second implementation of it. A record with nothing to change
     * has its staged copy dropped again, and so is never written at all.
     */
    private fun sweepRecord(uuid: UUID, staging: MergeStaging, banked: MutableList<BankedPosition>): Boolean {
        val live = recordsDir.resolve("$uuid$RECORD_SUFFIX")
        if (Files.notExists(live)) return false
        val staged = staging.replacing(live)
        Files.copy(live, staged)
        val store = JsonPlayerStore(staged.parent)
        var changed = false

        // Read before anything is rewritten: which bucket is the banked one turns
        // on the World the player was last in, and that field is about to change.
        val lastWorld = store.lastWorld(uuid)
        val liveWorld = lastWorld?.let(WorldLayout::byId) ?: WorldLayout.PRIMARY
        val bankedWorld = WorldLayout.all.first { it !== liveWorld }

        val secondary = store.bucket(uuid, WorldLayout.SECONDARY.id)
        val movedSecondary = secondary?.let { MergedPlayerdata.merged(it, offset) }
        if (movedSecondary != null && movedSecondary != secondary) {
            store.setBucket(uuid, WorldLayout.SECONDARY.id, movedSecondary)
            changed = true
        }

        // The banked position is the *other* World's bucket, in merged
        // coordinates — which for Secondary's is the moved one and for Primary's
        // is the bucket exactly as it stands, because Primary did not move.
        val bankedBucket = if (bankedWorld === WorldLayout.SECONDARY) {
            movedSecondary
        } else {
            store.bucket(uuid, WorldLayout.PRIMARY.id)
        }
        bankedBucket?.let { bankedPosition(uuid, bankedWorld, it) }?.let(banked::add)

        // There is one World after this, so a record still naming Secondary would
        // send its owner to a World that is being retired.
        if (lastWorld == WorldLayout.SECONDARY.id) {
            store.setLastWorld(uuid, WorldLayout.PRIMARY.id)
            changed = true
        }

        if (!changed) {
            Files.delete(staged)
            return false
        }
        stamp(staged)
        return true
    }

    /**
     * [bucket] as the signpost will read it, or null when there is nothing worth
     * telling the player: a bucket in Secondary's End names a place that will not
     * exist, and a bucket whose dimension role we cannot read names nowhere at
     * all — better to say nothing than to send someone to a guess.
     */
    private fun bankedPosition(uuid: UUID, world: WorldTrio, bucket: PerWorldBucket): BankedPosition? {
        val role = DimensionRole.fromId(bucket.dimension) ?: return null
        if (role == DimensionRole.END && world === WorldLayout.SECONDARY) return null
        return BankedPosition(
            uuid = uuid,
            world = world.id,
            dimension = WorldLayout.PRIMARY.dimensionId(role),
            x = bucket.x,
            y = bucket.y,
            z = bucket.z,
        )
    }

    /** Adds the merge stamp to a staged record, leaving every other field's bytes alone. */
    private fun stamp(record: Path) = MergeStamp.into(record, offset, at)

    // ---- the artifact the signpost reads ------------------------------------

    /**
     * The banked positions, as one read-only file the server loads rather than as
     * a field on each player's record — because that is what it is for. Nothing
     * ever writes to it again, `/switch` only ever reads it, and one file per
     * merge can be archived and deleted the day the last player has been told.
     *
     * One player per line, so an operator can grep it for a name that asks.
     */
    private fun writeBankedPositions(staging: MergeStaging, banked: List<BankedPosition>) {
        val players = banked.joinToString(",\n") { position ->
            """    ${PortalJson.encodeString(position.uuid.toString())}: """ +
                """{"world":${PortalJson.encodeString(position.world)},""" +
                """"dimension":${PortalJson.encodeString(position.dimension)},""" +
                """"x":${position.x},"y":${position.y},"z":${position.z}}"""
        }
        Files.writeString(
            staging.adding(plan.targetDir.resolve(BANKED_POSITIONS_FILE)),
            """
            |{
            |  "mergedAt": ${PortalJson.encodeString(at.toString())},
            |  "offset": {"x": ${offset.x}, "z": ${offset.z}},
            |  "players": {
            |$players
            |  }
            |}
            |
            """.trimMargin(),
        )
    }

    private companion object {
        const val PLAYERDATA_DIRECTORY = "playerdata"
        const val RECORDS_DIRECTORY = "${WorldMerge.MOD_DIRECTORY}/players"

        /**
         * Where the merge leaves every banked position. The name comes from the
         * signpost that reads it back, so the writer and the only reader cannot
         * drift apart into two files neither of them finds.
         */
        const val BANKED_POSITIONS_FILE = "${WorldMerge.MOD_DIRECTORY}/${BankedPositions.FILE_NAME}"

        const val LIVE_SAVE_SUFFIX = ".dat"

        /** Vanilla's live save and the backup `PlayerDataStorage.load` falls back to. */
        val SAVE_SUFFIXES = listOf(LIVE_SAVE_SUFFIX, ".dat_old")
        const val RECORD_SUFFIX = ".json"
    }
}
