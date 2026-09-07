package eu.mctraveler.importer

import eu.mctraveler.persistence.PortalJson
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The merge's own record of what it did to a save — [WorldMerge.MARKER_FILE],
 * `mctraveler/merge.json` — written by the run that moved the landmass and read
 * back by everything that has to agree with it afterwards (ticket 14).
 *
 * It is the answer to "how far did Secondary move?" for the life of the save,
 * and it is deliberately the *only* answer. The merge is offline and happens
 * once, but the Portal cutover left roughly thirteen thousand quarantined saves
 * that are claimed lazily as their owners return — some of them years from now —
 * and every one of those claims has to apply the very move the sweep applied on
 * the night. Taking that number from the file the merge itself wrote means there
 * is one value, produced by the operation that actually happened, with no step
 * between planning and running that anyone can skip and nothing to keep in sync.
 *
 * The two ends of it are here together, [contents] and [offsetApplied], for the
 * same reason: a writer and a reader in different files can drift into two
 * spellings of the same fact, and this is the one fact that must still be true
 * years after everybody has stopped watching.
 */
class MergeMarker(
    /** Where the marker is, present or not. Its absence is an answer; see [offsetApplied]. */
    private val file: Path,
) {

    /**
     * How far Secondary moved on this deployment, or null when the save carries
     * no marker at all.
     *
     * The three cases are three different things, and collapsing any two of them
     * would be the failure this type exists to prevent:
     *
     * - **No marker**: this save has not been merged. Every server before the
     *   operation and every server that never runs it is in this state, and a
     *   claim on one behaves exactly as it did before the merge existed.
     * - **A marker that reads**: the offset it names, which is the offset the
     *   landmass actually moved by.
     * - **A marker that does not read**: this save says it was merged and cannot
     *   say by how much, so it throws. Treating that as "not merged" would leave
     *   every returning player silently at their pre-merge coordinates, once
     *   each and with no second chance — the exact silence this is here to make
     *   impossible. The caller turns it into a refusal an operator can see; see
     *   [OrphanedSaveClaim].
     *
     * An offset of zero on both axes is refused with the unreadable ones rather
     * than honoured. No merge can have applied it — the placement search will not
     * return the origin and refuses an operator who supplies it, because it would
     * leave Secondary exactly where it is — so a marker naming it is a damaged
     * marker wearing the shape of a legitimate one, and honouring it would move
     * nobody just as quietly as no marker at all.
     */
    fun offsetApplied(): MergeOffset? {
        if (Files.notExists(file)) return null
        return try {
            val fields = PortalJson.parse(Files.readString(file))
            val offset = MergeOffset(axis(fields, OFFSET_X), axis(fields, OFFSET_Z))
            require(offset.x != 0 || offset.z != 0) {
                "it records a move of ${offset.x}, ${offset.z}, which would leave Secondary exactly " +
                    "where it was — no merge can have applied that"
            }
            offset
        } catch (unreadable: Exception) {
            throw IllegalStateException(
                "$file says this save has been merged but cannot be read, so how far Secondary moved " +
                    "is unknown: ${unreadable.message}. Refusing to read that as an unmerged save — " +
                    "doing so would put every returning player back at their pre-merge coordinates, " +
                    "silently and once each. Restore the file from the merge's own report or from a " +
                    "backup before anyone else logs in.",
                unreadable,
            )
        }
    }

    /** One axis of the offset, complaining in terms of the marker rather than of JSON. */
    private fun axis(fields: Map<String, PortalJson.Field>, name: String): Int {
        val field = fields[name] ?: throw IllegalArgumentException("it has no \"$name\"")
        return field.rawValue.toIntOrNull()
            ?: throw IllegalArgumentException("its \"$name\" is not a whole number of blocks (${field.rawValue})")
    }

    companion object {
        /** The marker of the save in [runDirectory], at the one path the merge writes it to. */
        fun of(runDirectory: Path): MergeMarker = MergeMarker(runDirectory.resolve(WorldMerge.MARKER_FILE))

        /**
         * The bytes a finished merge leaves on the save it merged.
         *
         * The instant is what makes a marker readable by a human, and the offset
         * is what makes it readable by [offsetApplied] — months later the
         * question asked of a save is not only whether it was merged but by how
         * far, and a coordinate that looks wrong is diagnosable from this one
         * file.
         */
        fun contents(offset: MergeOffset, at: Instant): String =
            """{"mergedAt":${PortalJson.encodeString(at.toString())},""" +
                """"$OFFSET_X":${offset.x},"$OFFSET_Z":${offset.z}}""" + "\n"

        private const val OFFSET_X = "offsetX"
        private const val OFFSET_Z = "offsetZ"
    }
}
