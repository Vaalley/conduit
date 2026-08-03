package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag

/** One kind of `dimension` field found in the saved maps, and what became of it. */
data class MapKind(val what: String, val count: Int, val outcome: String) {
    fun describe(): String = "$what — $count map${if (count == 1) "" else "s"}, $outcome"
}

/**
 * What the map sweep found and what it moved (merge spec, User Story 3).
 *
 * Every kind is listed, including the ones it deliberately did not touch. A
 * sweep that reported only its successes would read identically whether it had
 * understood the remaining formats or merely failed to notice them.
 */
data class MapSweepReport(val kinds: List<MapKind>, val unreadable: Int) : MergeSection {

    val moved: Int get() = kinds.filter { it.outcome.startsWith("moved") }.sumOf { it.count }

    override fun lines(): List<String> = listOf(
        reportLine(
            "maps moved",
            if (moved == 0) "none pointed into Secondary" else "$moved onto the relocated landmass",
        ),
    ) + kinds.map { reportLine("  maps", it.describe()) } +
        if (unreadable == 0) {
            emptyList()
        } else {
            listOf(
                reportLine(
                    "  maps unreadable",
                    "$unreadable could not be read and were left exactly as they are — they were " +
                        "already unreadable before the merge",
                ),
            )
        }
}

/**
 * The saved maps that show Secondary, pointed at where Secondary now is (merge
 * spec, User Story 3; ticket 23).
 *
 * A filled map carries a `map_id` and nothing else: the centre and the dimension
 * live in a level-wide `data/minecraft/maps/<id>.dat`. That is not chunk data and
 * not inside Secondary's dimension folder, so the relocation never saw it — the
 * item frame on the wall moved and the picture inside it did not follow.
 *
 * **These were already inert, and that is the reason to be careful rather than a
 * reason to relax.** They name dimensions this server does not register, so they
 * have shown a frozen picture and tracked nobody since the Portal cutover. Doing
 * nothing leaves them exactly as they have been. Doing it *wrong* is worse than
 * doing nothing: a map whose dimension is corrected but whose centre is not now
 * names real, populated Primary ground and shows somebody confidently the wrong
 * place. So the two fields move together or not at all.
 *
 * **What can be decided, and what cannot.** Two eras are stored here and only one
 * of them is self-describing:
 *
 * - **Named** — `minecraft:last` and `minecraft:last_nether`, the Portal's own
 *   backend ids. Unambiguous. `minecaft:last` is here too, with a letter missing,
 *   because eleven files really do spell it that way and a sweep that ignored
 *   them would leave eleven maps behind for a typo nobody can now explain.
 * - **Numbered** — before 1.16 the field was an integer. The integers here are
 *   0, 1, −1, 12, 13 and 14, and that alone settles what they mean: an
 *   *environment* is only ever 0, −1 or 1, so these are world ids from the
 *   multiworld setup that came before. Which leaves the question of which id was
 *   Secondary, and the centres answer it — every one of the 1,604 maps under id
 *   12 sits inside ±50,000, hugging Secondary's border, while the maps under id 0
 *   run out past 500,000, which is Primary and could be nothing else.
 *
 * Ids 13 and 14 are twelve maps between them and are left alone. They are most
 * likely Secondary's nether and end, but "most likely" is not the standard for
 * rewriting somebody's map, and the report names them so the next person decides
 * with the evidence in front of them rather than discovering the gap.
 *
 * A rewritten legacy map keeps its integer: the id becomes Primary's rather than
 * a modern string, so the file stays consistent with the version that wrote it
 * and vanilla's own fixer converts it on load exactly as it always would have.
 */
class MapSweep(
    private val mapsDir: Path,
    private val offset: MergeOffset,
    /** Where each rewritten file is built; the caller commits. Null means report only. */
    private val staging: ((Path) -> Path)? = null,
) {

    fun sweep(): MapSweepReport {
        if (!Files.isDirectory(mapsDir)) return MapSweepReport(emptyList(), unreadable = 0)
        val files = Files.newDirectoryStream(mapsDir, "*.dat").use { it.sortedBy(Path::toString) }

        var unreadable = 0
        val counts = LinkedHashMap<String, Int>()
        val outcomes = LinkedHashMap<String, String>()

        for (file in files) {
            val root = try {
                NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            } catch (failure: Exception) {
                // Eleven of these were unreadable before the merge and are
                // unreadable now. Reading them is not this phase's job and
                // rewriting them is certainly not.
                unreadable++
                continue
            }
            val data = root.getCompoundOrEmpty(DATA).takeIf { !it.isEmpty } ?: root
            val was = data.get(DIMENSION)
            val key = describe(was)
            counts[key] = (counts[key] ?: 0) + 1

            val role = secondaryRole(key)
            if (role == null) {
                outcomes[key] = leftAlone(key)
                continue
            }
            outcomes[key] = "moved ${offset.describe(role)}"
            val stage = staging ?: continue

            data.put(DIMENSION, primaryFor(was, role))
            data.putInt(X_CENTER, offset.mergedX(data.getIntOr(X_CENTER, 0), role))
            data.putInt(Z_CENTER, offset.mergedZ(data.getIntOr(Z_CENTER, 0), role))
            NbtIo.writeCompressed(root, stage(file))
        }

        return MapSweepReport(
            kinds = counts.map { (what, count) -> MapKind(what, count, outcomes[what] ?: leftAlone(what)) },
            unreadable = unreadable,
        )
    }

    /** Which of Secondary's dimensions a map's field names, or null if it is not Secondary's. */
    private fun secondaryRole(field: String): DimensionRole? = when (field) {
        SECONDARY_OVERWORLD, SECONDARY_OVERWORLD_TYPO, "<int $LEGACY_SECONDARY_OVERWORLD>" ->
            DimensionRole.OVERWORLD
        SECONDARY_NETHER -> DimensionRole.NETHER
        else -> null
    }

    /** The same field, naming Primary's matching dimension in the era the file is written in. */
    private fun primaryFor(was: Tag?, role: DimensionRole): Tag =
        if (was is IntTag) {
            IntTag.valueOf(if (role == DimensionRole.NETHER) LEGACY_PRIMARY_NETHER else LEGACY_PRIMARY_OVERWORLD)
        } else {
            StringTag.valueOf(WorldLayout.PRIMARY.dimensionId(role))
        }

    private fun leftAlone(field: String): String = when (field) {
        "<int $LEGACY_UNKNOWN_A>", "<int $LEGACY_UNKNOWN_B>" ->
            "left alone — most likely Secondary's nether and end, but only likely, and a map is " +
                "not worth rewriting on a guess"
        else -> "left alone — Primary's own"
    }

    private fun describe(tag: Tag?): String = when {
        tag == null -> "<absent>"
        tag.asString().isPresent -> tag.asString().get()
        tag.asInt().isPresent -> "<int ${tag.asInt().get()}>"
        else -> "<${tag.type.name}>"
    }

    private companion object {
        const val DATA = "data"
        const val DIMENSION = "dimension"
        const val X_CENTER = "xCenter"
        const val Z_CENTER = "zCenter"

        /** The Portal's own backend ids, which is what a modern map here still names. */
        const val SECONDARY_OVERWORLD = "minecraft:last"
        const val SECONDARY_NETHER = "minecraft:last_nether"

        /**
         * Eleven files spell it with the `r` missing. Whatever wrote them is long
         * gone and the maps are real, so the sweep recognises the typo rather than
         * leaving eleven maps behind out of tidiness.
         */
        const val SECONDARY_OVERWORLD_TYPO = "minecaft:last"

        /** Secondary's id in the multiworld setup that came before; see the class note. */
        const val LEGACY_SECONDARY_OVERWORLD = 12
        const val LEGACY_PRIMARY_OVERWORLD = 0
        const val LEGACY_PRIMARY_NETHER = -1

        /** Twelve maps that are probably Secondary's other two dimensions, and are left alone. */
        const val LEGACY_UNKNOWN_A = 13
        const val LEGACY_UNKNOWN_B = 14
    }
}
