package eu.mctraveler.worlds

import eu.mctraveler.MCTraveler
import eu.mctraveler.persistence.PortalJson
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID

/**
 * Where one player's *other* base ended up, in the merged map's own coordinates.
 *
 * [world] is the World the base was in — the word the player recognises the
 * place by, and the only part of this that still describes the past. [dimension]
 * and the coordinates are where it is **now**: the merge moved the landmass and
 * recorded the result here, so nothing has to be re-derived at read time.
 */
data class OtherBase(
    val world: String,
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    /**
     * The World as a player has always seen it written. The artifact records the
     * Portal's world id (`primary`/`secondary`), because that is the key every
     * other record on disk is written under; nobody has ever been shown that
     * spelling.
     */
    val worldName: String get() = world.replaceFirstChar(Char::uppercaseChar)
}

/**
 * The merge's record of where every player's other base went, read back for the
 * `/switch` signpost.
 *
 * The merge writes `mctraveler/banked-positions.json` once, offline, and only
 * when at least one player actually had a banked position. So the file's absence
 * is a normal state and not a fault: every server that has never been merged is
 * in it, and so is a merged one on which nobody kept two bases. A player with no
 * entry never had another base, and the honest thing to tell them is nothing at
 * all.
 *
 * The parse is cached against the file's size and modification time rather than
 * done per command. In production that is one read for the life of the server —
 * nothing writes this file while the server is up — but re-reading a file that
 * *has* changed costs one stat call and means an operator who repairs or drops
 * in the artifact does not have to restart thirteen thousand players' server to
 * make it count.
 *
 * A file that will not parse is logged and treated as absent. The signpost is
 * the one thing that must never be the reason a player cannot be answered, and
 * the operator has both the log line and the merge's own report; taking the
 * server down over a cosmetic file would be the more expensive failure.
 */
class BankedPositions(private val file: Path) {

    private var stamp: Stamp? = UNREAD
    private var byPlayer: Map<UUID, OtherBase> = emptyMap()

    /** Where [uuid]'s other base is now, or null if the merge recorded none for them. */
    fun of(uuid: UUID): OtherBase? {
        refresh()
        return byPlayer[uuid]
    }

    private fun refresh() {
        val current = stampOf()
        if (current == stamp) return
        stamp = current
        byPlayer = if (current == null) emptyMap() else parse()
    }

    private fun parse(): Map<UUID, OtherBase> = try {
        val players = PortalJson.parse(Files.readString(file))[PLAYERS]
            ?: throw IllegalArgumentException("no \"$PLAYERS\" object")
        PortalJson.parse(players.rawValue).entries.associate { (uuid, entry) ->
            UUID.fromString(uuid) to otherBase(PortalJson.parse(entry.rawValue))
        }
    } catch (failure: Exception) {
        MCTraveler.LOGGER.error("could not read $file — no player will be told where their other base went", failure)
        emptyMap()
    }

    private fun otherBase(fields: Map<String, PortalJson.Field>): OtherBase = OtherBase(
        world = PortalJson.decodeString(raw(fields, "world")),
        dimension = PortalJson.decodeString(raw(fields, "dimension")),
        x = number(fields, "x"),
        y = number(fields, "y"),
        z = number(fields, "z"),
    )

    private fun raw(fields: Map<String, PortalJson.Field>, key: String): String =
        requireNotNull(fields[key]) { "a banked position is missing \"$key\"" }.rawValue

    private fun number(fields: Map<String, PortalJson.Field>, key: String): Double {
        val raw = raw(fields, key)
        return raw.toDoubleOrNull() ?: throw IllegalArgumentException("a banked position's \"$key\" is not a number: $raw")
    }

    /** The file as it stands, or null when it is not there. */
    private fun stampOf(): Stamp? = try {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        Stamp(attributes.size(), attributes.lastModifiedTime())
    } catch (absent: IOException) {
        null
    }

    /** What the file looked like when it was last parsed. */
    private data class Stamp(val size: Long, val modifiedAt: FileTime)

    companion object {
        /** The merge's artifact, under the mod's own directory beside the player records. */
        const val FILE_NAME = "banked-positions.json"

        private const val PLAYERS = "players"

        /**
         * A stamp no file can have, so the very first lookup always reads. It is
         * distinct from null, which is the real answer "there is no file".
         */
        private val UNREAD = Stamp(-1L, FileTime.fromMillis(0L))
    }
}
