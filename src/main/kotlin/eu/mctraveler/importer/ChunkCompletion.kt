package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.level.ChunkPos
import java.nio.file.Path

/**
 * One kind of coordinate the relocation tool left behind, and how many of them
 * the merge finished.
 *
 * The kind is the field's name as the walk found it — `minecraft:beehive.bees`,
 * `minecraft:bee.hive_pos` — because a count on its own says the tool is behind
 * without saying what it is behind *on*, and the name is what somebody widening
 * the patch needs.
 */
data class CompletedCoordinates(val what: String, val count: Int) {
    fun describe(): String = "$what — $count coordinate${if (count == 1) "" else "s"}"
}

/**
 * What the merge had to finish after the relocation tool had run (ticket 17).
 *
 * **A non-zero count here is a finding, not a formality.** It means the pinned
 * MCA Selector build does not know about a coordinate that Minecraft 26.2 writes,
 * and that the merge completed it rather than stopping. The kinds are named so
 * that the next person to widen `gradle/mcaselector/2.8-mctraveler1.patch` knows
 * what to widen it *with*, and so that a tool falling further behind between one
 * rehearsal and the next is visible as a number going up.
 */
data class ChunkCompletionReport(
    val chunksCompleted: Int,
    val kinds: List<CompletedCoordinates>,
) : MergeSection {

    val completed: Int get() = kinds.sumOf { it.count }

    override fun lines(): List<String> = listOf(
        reportLine(
            "coordinates completed",
            if (completed == 0) {
                "none — the relocation tool moved everything it should have"
            } else {
                "$completed in $chunksCompleted chunk${if (chunksCompleted == 1) "" else "s"}, " +
                    "which the relocation tool did not move. See below."
            },
        ),
    ) + kinds.map { reportLine("  the tool left behind", it.describe()) } +
        if (completed == 0) {
            emptyList()
        } else {
            listOf(
                reportLine(
                    "  what this means",
                    "MCA Selector has fallen behind what Minecraft writes. The merge finished " +
                        "these itself and the audit below still checked all of them, so the map is " +
                        "sound — but the patch wants widening before the next run.",
                ),
            )
        }
}

/**
 * The coordinates the relocation tool forgot, finished before the audit looks
 * (merge spec, "Relocation"; ticket 17).
 *
 * MCA Selector moves an entity's and a block entity's positions from two
 * hand-written switches — one over entity ids, one over block entity ids — and
 * neither is complete. Four gaps were found by pointing [ChunkAudit] at real
 * relocated chunks, each one discovered by running into it, and the patched build
 * closed those four and the wider set ticket 17 enumerated from Minecraft's own
 * classes. **There is no reason to think that set is final.** The next version
 * that gives an entity somewhere to remember will be found the same way, and the
 * worst possible moment to find it is in the downtime window with the server down
 * and the merge refusing.
 *
 * So this runs between the relocation and the audit and applies the offset to
 * coordinates that should have moved and did not, by the same shape rule the audit
 * uses to find them. A merge that would have refused now finishes.
 *
 * **The objection, and the answer.** Ticket 03 deliberately refused to let the
 * audit repair structural leftovers, and its reason was right: *an audit that
 * patches over the relocation's gaps stops being able to tell anyone the gaps are
 * there.* That is why this is a phase of its own with a section of its own, and
 * why it reports every coordinate it completed **and what kind it was**. The
 * information ticket 03 protected is preserved exactly; what changes is that the
 * merge finishes and tells you, instead of stopping and telling you. The audit
 * still runs afterwards, unchanged, and still refuses over anything left standing.
 *
 * **What counts as a coordinate** is ticket 03's rule, and deliberately the same
 * one, because a shape this completes but the audit would not classify the same way
 * is a gap neither could see:
 *
 * - an int array of exactly three — `BlockPos.CODEC`'s own encoding, so a bee's
 *   hive, a point-of-interest record and a villager's memory alike. Four is a uuid,
 *   which is the one int array in this format that is not a place;
 * - a compound carrying `x`, `y` and `z` ints — the block entity and scheduled tick
 *   spelling.
 *
 * **What it deliberately leaves alone**, each for ticket 03's own reason:
 *
 * - **A velocity is not a place.** `Motion` is three doubles near the origin, which
 *   reads as a coordinate inside Secondary's old footprint every single time.
 *   `Pos` is the only list of three doubles that is a place, and the relocation
 *   moves it, so nothing here has to.
 * - **The arbitrary-NBT escape hatches** (`minecraft:custom_data`, `entity_data`,
 *   `block_entity_data`, `bucket_entity_data`), matching ticket 06 and the audit. A
 *   bucketed axolotl's stored position is not a place anyone stands.
 * - **The chunk's own frame** — its position, its structure starts, the references
 *   that point back at them. Those are not coordinates the chunk *carries*, they
 *   are what the chunk *is*, and they are tied to which region file and which slot
 *   it is stored in. Rewriting one would not finish the relocation's job, it would
 *   make the chunk lie about where it is; a leftover there means the relocation
 *   failed structurally, which is exactly the finding ticket 03 refused to let
 *   anything paper over. Those stay the audit's to refuse.
 * - **An exit portal in a dimension the merge is deleting.** Secondary's End is
 *   discarded rather than relocated, so an end gateway's `exit_portal` has no
 *   relocated destination to be pointed at and moving it by the overworld's offset
 *   would invent one — the same reasoning the audit already uses to name rather
 *   than move a lodestone compass pointing into Secondary's End.
 *
 * **Only coordinates that are actually leftovers are touched.** A coordinate is
 * completed exactly when it still falls inside Secondary's footprint as the
 * placement measured it, which is the audit's test and no other, so a coordinate
 * that already moved is never moved twice.
 */
class ChunkCompletion(
    /** Primary's dimensions as they are being rebuilt inside the staging area. */
    private val stagedLevelDir: Path,
    private val placement: MergePlacement,
) {

    private val chunks = StagedChunks(stagedLevelDir)
    private val kinds = LinkedHashMap<String, Int>()
    private var chunksCompleted = 0

    fun run(): ChunkCompletionReport {
        placement.dimensions.forEach(::complete)
        return ChunkCompletionReport(
            chunksCompleted = chunksCompleted,
            kinds = kinds.entries.map { CompletedCoordinates(it.key, it.value) },
        )
    }

    private fun complete(where: DimensionPlacement) {
        val dimension = WorldLayout.PRIMARY.dimension(where.role)
        val storage = Footprint.storageFolder(stagedLevelDir, dimension)
        for ((folder, type) in FOLDERS) {
            val said = "completing ${dimension.identifier().path} $type"
            chunks.walk(storage.resolve(folder), dimension, type, said) { chunk, tag ->
                ChunkWalk(where, chunk).run(tag, type)
            }
        }
    }

    /**
     * One chunk's worth of NBT, walked for coordinates that stayed behind.
     *
     * The walk is the audit's, minus the parts the audit reads *by name* — the
     * chunk's own position, its structures, the command blocks it lists and the
     * lodestone targets it repairs. Those are either not this phase's to finish or
     * already somebody's, and reaching them here would either double a repair or
     * silence a refusal that ought to happen.
     */
    private inner class ChunkWalk(private val where: DimensionPlacement, private val chunk: ChunkPos) {

        private var completed = false

        fun run(tag: CompoundTag, what: String): Boolean {
            walk(tag, what)
            if (completed) chunksCompleted++
            return completed
        }

        private fun walk(tag: CompoundTag, what: String) {
            // The name of the nearest thing that has one, so a finding reads
            // "minecraft:beehive.bees" rather than a path from the root.
            val here = tag.getStringOr(ID, "").ifEmpty { what }

            blockPos(here, tag, X, Y, Z)
            blockPos(here, tag, LEGACY_TILE_X, LEGACY_TILE_Y, LEGACY_TILE_Z)

            for ((key, value) in tag.entrySet()) {
                when {
                    // Somebody's stored data rather than the world's geography, and
                    // a place in a dimension being deleted has nowhere to be moved
                    // to; both are spelled out in this class's own documentation.
                    key in OPAQUE || key in NOT_OURS_TO_MOVE -> Unit
                    // Repaired by the audit, which knows the dimension a target
                    // names; completing it here would apply the offset twice.
                    key == LODESTONE_TRACKER -> Unit
                    value is CompoundTag -> walk(value, "$here.$key")
                    value is ListTag -> value.forEach { if (it is CompoundTag) walk(it, "$here.$key") }
                    value is IntArrayTag -> completeIntArray(tag, key, "$here.$key")
                    else -> Unit
                }
            }
        }

        /** An int array of exactly three, which is how a block position is written. */
        private fun completeIntArray(tag: CompoundTag, key: String, what: String) {
            val at = tag.getIntArray(key).orElse(null) ?: return
            if (at.size != BLOCK_POS_LENGTH) return
            if (!where.secondary.containsBlock(at[0], at[2])) return
            tag.putIntArray(
                key,
                intArrayOf(
                    placement.offset.mergedX(at[0], where.role),
                    at[1],
                    placement.offset.mergedZ(at[2], where.role),
                ),
            )
            record(what)
        }

        /**
         * A compound that *is* a block position, under whichever of its spellings.
         * All three have to be there and all three have to be ints: a compound
         * carrying an `x` that is a double is somebody's data and not a place.
         */
        private fun blockPos(what: String, tag: CompoundTag, x: String, y: String, z: String) {
            val atX = tag.getInt(x).orElse(null) ?: return
            val atY = tag.getInt(y).orElse(null) ?: return
            val atZ = tag.getInt(z).orElse(null) ?: return
            if (!where.secondary.containsBlock(atX, atZ)) return
            tag.putInt(x, placement.offset.mergedX(atX, where.role))
            tag.putInt(y, atY)
            tag.putInt(z, placement.offset.mergedZ(atZ, where.role))
            record("$what.$x/$y/$z")
        }

        private fun record(what: String) {
            kinds[what] = (kinds[what] ?: 0) + 1
            completed = true
        }
    }

    private companion object {
        val FOLDERS = listOf(
            StagedChunks.TERRAIN to StagedChunks.TERRAIN_TYPE,
            StagedChunks.ENTITIES to StagedChunks.ENTITIES_TYPE,
            StagedChunks.POI to StagedChunks.POI_TYPE,
        )

        const val ID = "id"
        const val X = "x"
        const val Y = "y"
        const val Z = "z"

        const val LEGACY_TILE_X = "TileX"
        const val LEGACY_TILE_Y = "TileY"
        const val LEGACY_TILE_Z = "TileZ"

        const val LODESTONE_TRACKER = "minecraft:lodestone_tracker"

        const val BLOCK_POS_LENGTH = 3

        /**
         * The arbitrary-NBT escape hatches, whose contents are somebody's stored
         * data rather than the world's geography.
         */
        val OPAQUE = setOf(
            "minecraft:custom_data",
            "minecraft:entity_data",
            "minecraft:block_entity_data",
            "minecraft:bucket_entity_data",
        )

        /**
         * An end gateway's exit portal, which names a place in the End. Secondary's
         * End is discarded rather than relocated, so there is no destination to
         * point it at and the overworld's offset would invent one.
         */
        val NOT_OURS_TO_MOVE = setOf("exit_portal", "ExitPortal")
    }
}
