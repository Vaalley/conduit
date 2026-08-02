package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Path
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level

/**
 * One coordinate a relocated chunk still records in the place Secondary used to
 * be — the thing this whole phase exists to find (merge spec, User Story 15).
 */
data class StaleCoordinate(
    val role: DimensionRole,
    val chunk: ChunkPos,
    /** The field, named from the nearest thing that has an id: `minecraft:item_frame.block_pos`. */
    val what: String,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    fun describe(): String = "in chunk ${chunk.x}, ${chunk.z}: $what still names $x, $y, $z"
}

/**
 * A command block whose command spells coordinates out, listed for the operator
 * and never rewritten (merge spec, User Story 20; "Out of Scope").
 *
 * A command is a program, and the numbers in one can be a place, a count, a
 * score or a tick — so rewriting them is guesswork with a redstone contraption on
 * the other end of it. The merge states where each one is and what it says, and
 * the operator decides.
 */
data class LiteralCoordinates(
    val role: DimensionRole,
    val x: Int,
    val y: Int,
    val z: Int,
    val command: String,
) {
    fun describe(): String = "${role.id} $x, $y, $z — $command"
}

/**
 * What the audit walked, what it repaired on its own, and what it is handing to
 * the operator (merge spec, User Stories 17 and 48).
 *
 * The two tiers are reported apart because the operator does different things
 * with them: [retargeted] is work already done and needs only to be believed,
 * while [commandBlocks] and [unrepairable] are an action list for after the
 * server is back up.
 */
data class ChunkAuditReport(
    val chunksAudited: Int,
    val coordinatesChecked: Int,
    /** Lodestone compass targets the merge re-pointed at the relocated landmass. */
    val retargeted: Int,
    val commandBlocks: List<LiteralCoordinates>,
    /** Cosmetic leftovers the merge knows how to find but not how to fix. */
    val unrepairable: List<String>,
) : MergeSection {

    val needsAnOperator: Int get() = commandBlocks.size + unrepairable.size

    override fun lines(): List<String> = listOf(
        reportLine("chunks audited", "$chunksAudited"),
        reportLine("coordinates checked", "$coordinatesChecked"),
        reportLine(
            "repaired automatically",
            if (retargeted == 0) {
                "nothing needed it"
            } else {
                "$retargeted lodestone compass target${if (retargeted == 1) "" else "s"}"
            },
        ),
        reportLine(
            "needs an operator",
            if (needsAnOperator == 0) "nothing" else "$needsAnOperator, listed below and never rewritten",
        ),
    ) + commandBlocks.map { reportLine("  command block", it.describe()) } +
        unrepairable.map { reportLine("  cannot be repaired", it) }
}

/**
 * Proof that the relocation was complete (merge spec, "Audit"; ticket 03).
 *
 * [ChunkRelocation] hands the chunks to MCA Selector and counts what arrives, but
 * a chunk that arrived is not the same claim as a chunk that arrived *correct*. So
 * every relocated chunk is read back out of the staging area, every coordinate it
 * carries is classified, and the merge states — rather than hopes — that nothing
 * still points into the place Secondary used to be.
 *
 * Two tiers, because the consequences differ:
 *
 * - **Structural** — the chunk's own position, block entity positions, scheduled
 *   block and fluid ticks, structure starts and references, entity positions, the
 *   tile positions of item frames and paintings, brain memories and
 *   point-of-interest records. One of those left behind is a chest that cannot be
 *   opened or a villager pathing into nowhere, so it fails the whole merge and
 *   nothing is written (merge spec, User Story 16).
 * - **Cosmetic** — a lodestone compass that would read as uncalibrated, a command
 *   block that would send someone to the wrong place. Lodestone targets are
 *   retargeted here, wherever they are and however deeply nested; command blocks
 *   are listed and never touched. One odd command block written in 2019 does not
 *   get to block a cutover at 2am (merge spec, User Story 17).
 *
 * **This phase is not a formality, and it is what found the relocation defects.**
 * The stock MCA Selector 2.8 relocated a chunk's own frame, its block entities and
 * everything inside them, its scheduled ticks, its structures, its entity positions
 * and its point-of-interest records — but it still spoke the block-position
 * spellings 1.21.5's `InlineBlockPosFormatFix` replaced, and it abandoned every
 * entity before reaching its villager memories at all. So a 26.2 save's leashes,
 * item frame and painting tile positions and villager memories arrived in Primary
 * still naming Secondary. This audit is what refused those merges rather than
 * letting a broken map open, and ticket 16 is what fixed the tool; the merge now
 * runs a patched build and `WorldMergeAuditTest` asserts each of the four arrives.
 *
 * It is still not a formality afterwards. The tool moves an entity's positions from
 * a list of the entity types that have them, and that list is not complete — a
 * bee's `hive_pos` is one it does not know — so this remains the thing standing
 * between an incomplete relocation and a map that opens broken.
 *
 * And one cross-check, because self-consistency is not correctness: a villager
 * remembering a bed, a workstation or a meeting point must find a
 * point-of-interest record at the same place, or the trading hall is quietly dead
 * even though every coordinate in it moved.
 *
 * **How a leftover is recognised.** A coordinate is a leftover when it still
 * falls inside Secondary's footprint as the placement measured it. That footprint
 * is a rectangle of whole region files ([RegionFileArea]) and so is slightly
 * over-inclusive — a coordinate in an empty corner of a Secondary region file
 * counts — which is the safe direction to be wrong in, since such a coordinate is
 * a leftover either way. The one case it cannot answer is an offset small enough
 * that the landed footprint overlaps the old one; the placement makes that
 * unreachable in practice, because the search only ever lands Secondary clear of
 * Primary's chunk data and Primary's own data is what covers Secondary's old
 * ground. Ticket 04's sampled block-for-block diff is the evidence that does not
 * depend on this test at all.
 *
 * **Nothing here writes to the live save.** The audit reads and repairs the
 * *staged* chunk data, which [MergeStaging] commits with everything else or not at
 * all.
 */
class ChunkAudit(
    /** Primary's dimensions as they are being rebuilt inside the staging area. */
    private val stagedLevelDir: Path,
    private val placement: MergePlacement,
) {

    private val chunks = StagedChunks(stagedLevelDir)
    private var chunksAudited = 0
    private var coordinatesChecked = 0
    private var retargeted = 0
    private val commandBlocks = mutableListOf<LiteralCoordinates>()
    private val unrepairable = mutableListOf<String>()
    private val stale = mutableListOf<StaleCoordinate>()
    private val orphanedMemories = mutableListOf<String>()

    fun run(): ChunkAuditReport {
        placement.dimensions.forEach(::audit)
        refuseIfAnythingStayedBehind()
        refuseIfAVillagerLostItsPoi()
        return ChunkAuditReport(
            chunksAudited = chunksAudited,
            coordinatesChecked = coordinatesChecked,
            retargeted = retargeted,
            commandBlocks = commandBlocks.toList(),
            unrepairable = unrepairable.toList(),
        )
    }

    // ---- one dimension ------------------------------------------------------

    /**
     * One relocated dimension, in the order the cross-check needs: the
     * points of interest first, so that the villagers walked afterwards have
     * something to be checked against.
     */
    private fun audit(where: DimensionPlacement) {
        val dimension = WorldLayout.PRIMARY.dimension(where.role)
        val storage = Footprint.storageFolder(stagedLevelDir, dimension)

        val pointsOfInterest = mutableSetOf<BlockPosition>()
        walk(storage.resolve(POI), dimension, POI_TYPE) { chunk, tag ->
            collectPointsOfInterest(tag, pointsOfInterest)
            ChunkWalk(where, chunk).run(tag, POI_TYPE)
        }
        walk(storage.resolve(TERRAIN), dimension, TERRAIN_TYPE) { chunk, tag ->
            ChunkWalk(where, chunk).terrain(tag)
        }
        walk(storage.resolve(ENTITIES), dimension, ENTITIES_TYPE) { chunk, tag ->
            ChunkWalk(where, chunk).entities(tag, pointsOfInterest)
        }
    }

    /**
     * Every chunk of every region file under [folder], walked by [audit] and
     * written back only if the walk repaired something.
     *
     * The reading and writing is [StagedChunks]', shared with [ChunkCompletion] so
     * that the phase which finishes the relocation's leftovers and the phase which
     * proves none are left reach exactly the same chunks.
     */
    private fun walk(
        folder: Path,
        dimension: ResourceKey<Level>,
        type: String,
        audit: (ChunkPos, CompoundTag) -> Boolean,
    ) = chunks.walk(folder, dimension, type, said = "auditing ${dimension.identifier().path} $type") { chunk, tag ->
        chunksAudited++
        audit(chunk, tag)
    }

    /** Every point of interest a poi chunk records, for the villager cross-check. */
    private fun collectPointsOfInterest(tag: CompoundTag, into: MutableSet<BlockPosition>) {
        for ((_, section) in tag.getCompoundOrEmpty(POI_SECTIONS).entrySet()) {
            if (section !is CompoundTag) continue
            for (record in section.getListOrEmpty(POI_RECORDS)) {
                if (record !is CompoundTag) continue
                val at = record.getIntArray(GLOBAL_POS).orElse(null) ?: continue
                if (at.size == BLOCK_POS_LENGTH) into += BlockPosition(at[0], at[1], at[2])
            }
        }
    }

    // ---- walking one chunk --------------------------------------------------

    /**
     * One chunk's worth of NBT, and everything a coordinate can hide in.
     *
     * Most of it is found by *shape* rather than by key path, which is the same
     * reasoning ticket 06 used to find a player's global positions: a rule stated
     * once reaches a compass in the hotbar, a compass in a shulker box inside a
     * chest, and a compass in a component some future version adds, without any of
     * them being enumerated. The shapes are
     *
     * - an int array of exactly three — `BlockPos.CODEC`'s own encoding, and so a
     *   leash knot, an item frame's `block_pos`, a point-of-interest record, and
     *   the `pos` of every `GlobalPos` there is: a villager's memory of its bed and
     *   a compass's memory of its lodestone alike. Four would be a uuid, which is
     *   the one thing in this format that is an int array and not a place;
     * - a compound carrying `x`, `y` and `z` ints — a block entity, a scheduled
     *   block tick, a scheduled fluid tick.
     *
     * Three things are read by name instead. The chunk's own position and its
     * structure starts and references are the chunk's frame rather than something
     * carried inside it, and each has a spelling of its own. A lodestone tracker is
     * named because it is *repaired* rather than refused, and the walk has to know
     * to stop before the shape rules find its target a second time.
     *
     * A `Pos` of three doubles is an entity's place; every other list of three
     * doubles is not. `Motion` is a velocity, and a velocity near the origin would
     * otherwise read as a coordinate inside Secondary's old footprint every single
     * time.
     */
    private inner class ChunkWalk(private val where: DimensionPlacement, private val chunk: ChunkPos) {

        private var repaired = false

        /** A terrain chunk: its own frame, its structures, and everything standing in it. */
        fun terrain(tag: CompoundTag): Boolean {
            if (tag.contains(CHUNK_X)) {
                chunkPosition("the chunk's own position", tag.getIntOr(CHUNK_X, 0), tag.getIntOr(CHUNK_Z, 0))
            }
            structures(tag.getCompoundOrEmpty(STRUCTURES))
            return run(tag, TERRAIN_TYPE)
        }

        /** An entities chunk: its own frame, and every entity in it. */
        fun entities(tag: CompoundTag, pointsOfInterest: Set<BlockPosition>): Boolean {
            val at = tag.getIntArray(ENTITIES_POSITION).orElse(null)
            if (at != null && at.size == CHUNK_POS_LENGTH) {
                chunkPosition("the chunk's own position", at[0], at[1])
            }
            for (entity in tag.getListOrEmpty(ENTITIES_LIST)) {
                if (entity is CompoundTag) crossCheckMemories(entity, pointsOfInterest)
            }
            return run(tag, ENTITIES_TYPE)
        }

        fun run(tag: CompoundTag, what: String): Boolean {
            audit(tag, what)
            return repaired
        }

        // ---- the generic walk -----------------------------------------------

        private fun audit(tag: CompoundTag, what: String) {
            // The name of the nearest thing that has one, so a finding reads
            // "minecraft:item_frame.block_pos" rather than a path from the root.
            val here = tag.getStringOr(ID, "").ifEmpty { what }

            blockPos(here, tag, X, Y, Z)
            blockPos(here, tag, LEGACY_TILE_X, LEGACY_TILE_Y, LEGACY_TILE_Z)
            command(tag)

            for ((key, value) in tag.entrySet()) {
                when {
                    // Its target is a place, but a wrong one only costs a needle
                    // that will not spin — so it is repaired rather than refused,
                    // and the generic walk must not then see it as a leftover.
                    key == LODESTONE_TRACKER && value is CompoundTag -> retarget(value)
                    // Arbitrary NBT the game never reads as geography: a bucketed
                    // axolotl's stored position is not a place anyone stands.
                    // Ticket 06 left these alone in a player's save for the same
                    // reason, and consistency between the two matters more than
                    // either decision on its own.
                    key in OPAQUE -> Unit
                    // A place; every other list of three doubles is a velocity.
                    key == POS && value is ListTag && value.size == VEC3_LENGTH -> coordinate(
                        "$here.$key",
                        value.getDoubleOr(0, 0.0).toInt(),
                        value.getDoubleOr(1, 0.0).toInt(),
                        value.getDoubleOr(2, 0.0).toInt(),
                    )
                    value is CompoundTag -> audit(value, "$here.$key")
                    value is ListTag -> value.forEach { if (it is CompoundTag) audit(it, "$here.$key") }
                    value is IntArrayTag -> tag.getIntArray(key).orElse(null)?.let {
                        if (it.size == BLOCK_POS_LENGTH) coordinate("$here.$key", it[0], it[1], it[2])
                    }
                    else -> Unit
                }
            }
        }

        /**
         * A compound that *is* a block position, under whichever of its spellings.
         * All three have to be there and all three have to be ints: a compound
         * carrying an `x` that is a double is somebody's data and not a place, and
         * reading it as one would put a coordinate of zero inside Secondary's old
         * footprint and refuse the merge over nothing.
         */
        private fun blockPos(what: String, tag: CompoundTag, x: String, y: String, z: String) {
            val atX = tag.getInt(x).orElse(null) ?: return
            val atY = tag.getInt(y).orElse(null) ?: return
            val atZ = tag.getInt(z).orElse(null) ?: return
            coordinate(what, atX, atY, atZ)
        }

        // ---- the fields a chunk spells out ----------------------------------

        /**
         * Structure starts and the references that point back at them.
         *
         * A start records the chunk it began in and a bounding box per piece; a
         * reference is a packed chunk position in a long array. Both are read by
         * name because neither is any of the shapes above, and a structure whose
         * pieces still name Secondary would have vanilla generating into it.
         */
        private fun structures(tag: CompoundTag) {
            for ((id, start) in tag.getCompoundOrEmpty(STRUCTURE_STARTS).entrySet()) {
                if (start !is CompoundTag) continue
                if (start.contains(STRUCTURE_CHUNK_X)) {
                    chunkPosition(
                        "the structure start $id",
                        start.getIntOr(STRUCTURE_CHUNK_X, 0),
                        start.getIntOr(STRUCTURE_CHUNK_Z, 0),
                    )
                }
                pieces(start, id)
            }
            val references = tag.getCompoundOrEmpty(STRUCTURE_REFERENCES)
            for (id in references.keySet()) {
                val packed = references.getLongArray(id).orElse(null) ?: continue
                for (reference in packed) {
                    chunkPosition(
                        "a reference to the structure $id",
                        (reference and CHUNK_MASK).toInt(),
                        (reference shr Int.SIZE_BITS).toInt(),
                    )
                }
            }
        }

        /** Every piece of a structure start, to any depth, by its bounding box. */
        private fun pieces(start: CompoundTag, id: String) {
            for (piece in start.getListOrEmpty(STRUCTURE_CHILDREN)) {
                if (piece !is CompoundTag) continue
                piece.getIntArray(STRUCTURE_BOX).orElse(null)?.let {
                    if (it.size != BOUNDING_BOX_LENGTH) return@let
                    coordinate("a piece of the structure $id", it[0], it[1], it[2])
                    coordinate("a piece of the structure $id", it[3], it[4], it[5])
                }
                pieces(piece, id)
            }
        }

        // ---- the cosmetic tier ----------------------------------------------

        /**
         * One lodestone compass re-pointed at the lodestone's new place (merge
         * spec, "Audit"; User Story 17).
         *
         * MCA Selector cannot do this one and never could: the target carries the
         * *dimension* it is in, and the fact that `mctraveler:secondary` becomes
         * `minecraft:overworld` is this merge's own knowledge and nothing a
         * general-purpose relocation tool could be told. So the merge finishes the
         * job here, and because the walk that reaches this is a shape rather than
         * a path it reaches a compass in a shulker box inside a chest exactly as
         * readily as one lying in a hopper.
         *
         * A target in Secondary's End is named instead of moved. That dimension is
         * being deleted, and pointing the needle at Primary's End would invent
         * somewhere its owner has never been.
         */
        private fun retarget(tracker: CompoundTag) {
            val target = tracker.getCompound(LODESTONE_TARGET).orElse(null) ?: return
            val role = secondaryRole(target.getStringOr(GLOBAL_POS_DIMENSION, "")) ?: return
            val at = target.getIntArray(GLOBAL_POS).orElse(null) ?: return
            if (at.size != BLOCK_POS_LENGTH) return
            if (role == DimensionRole.END) {
                unrepairable += "a lodestone compass in ${where.role.id} at chunk ${chunk.x}, ${chunk.z} " +
                    "still points into Secondary's End, which the merge discards"
                return
            }
            target.putString(GLOBAL_POS_DIMENSION, WorldLayout.PRIMARY.dimensionId(role))
            target.putIntArray(
                GLOBAL_POS,
                intArrayOf(
                    placement.offset.mergedX(at[0], role),
                    at[1],
                    placement.offset.mergedZ(at[2], role),
                ),
            )
            retargeted++
            repaired = true
        }

        /**
         * A command block listed for the operator, and left exactly as it is.
         *
         * The position is the block entity's own — already relocated, so the
         * operator can stand on it — and falls back to the chunk's corner for a
         * command block riding a minecart, which has a `Pos` rather than an x/y/z.
         */
        private fun command(tag: CompoundTag) {
            val command = tag.getStringOr(COMMAND, "")
            if (command.isEmpty() || !namesLiteralCoordinates(command)) return
            commandBlocks += LiteralCoordinates(
                role = where.role,
                x = tag.getIntOr(X, chunk.x * BLOCKS_PER_CHUNK),
                y = tag.getIntOr(Y, 0),
                z = tag.getIntOr(Z, chunk.z * BLOCKS_PER_CHUNK),
                command = command,
            )
        }

        // ---- the cross-check ------------------------------------------------

        /**
         * A villager's memory of a bed, a workstation or a meeting point, checked
         * against the point-of-interest records that arrived with it.
         *
         * The two live in different files — the villager in `entities`, the record
         * in `poi` — so this is the one invariant the audit cannot establish by
         * reading a single chunk, and the one a relocation is most likely to break
         * by moving one of the three folders and not the others. A villager that
         * still remembers a workstation nothing claims will path to it forever and
         * never work again, which is a trading hall that is dead without looking
         * broken.
         */
        private fun crossCheckMemories(entity: CompoundTag, pointsOfInterest: Set<BlockPosition>) {
            val id = entity.getStringOr(ID, "an entity")
            val memories = entity.getCompoundOrEmpty(BRAIN).getCompoundOrEmpty(BRAIN_MEMORIES)
            for (memory in CLAIMED_PLACES) {
                val held = memories.getCompound(memory).orElse(null) ?: continue
                // A memory is written through `ExpirableValue`, which wraps what it
                // holds in `value`; a save from before that wrapper existed has the
                // global position at the top.
                val place = held.getCompound(MEMORY_VALUE).orElse(held)
                val at = place.getIntArray(GLOBAL_POS).orElse(null) ?: continue
                if (at.size != BLOCK_POS_LENGTH) continue
                if (BlockPosition(at[0], at[1], at[2]) in pointsOfInterest) continue
                orphanedMemories += "the $id in ${where.role.id} chunk ${chunk.x}, ${chunk.z} remembers " +
                    "its $memory at ${at[0]}, ${at[1]}, ${at[2]}, and no point-of-interest record arrived there"
            }
            for (passenger in entity.getListOrEmpty(PASSENGERS)) {
                if (passenger is CompoundTag) crossCheckMemories(passenger, pointsOfInterest)
            }
        }

        // ---- classifying one coordinate -------------------------------------

        private fun chunkPosition(what: String, x: Int, z: Int) =
            coordinate(what, x * BLOCKS_PER_CHUNK, 0, z * BLOCKS_PER_CHUNK)

        private fun coordinate(what: String, x: Int, y: Int, z: Int) {
            coordinatesChecked++
            if (!where.secondary.containsBlock(x, z)) return
            stale += StaleCoordinate(where.role, chunk, what, x, y, z)
        }
    }

    // ---- refusals -----------------------------------------------------------

    /**
     * The refusal the whole phase exists for (merge spec, User Story 16). It names
     * what was left behind and where, because "the relocation is incomplete" is
     * not something anyone can act on, and it happens before [MergeStaging]
     * commits anything at all — so a save that fails here is the save the operator
     * started with.
     */
    private fun refuseIfAnythingStayedBehind() {
        if (stale.isEmpty()) return
        val named = stale.take(LEFTOVERS_NAMED)
        throw MigrationRefused(
            "the relocated chunks still hold ${stale.size} coordinate" +
                "${if (stale.size == 1) "" else "s"} pointing into the place Secondary used to be, so the " +
                "merge stopped and nothing has been written:\n" +
                named.joinToString("\n") { "  ${it.role.id}: ${it.describe()}" } +
                (if (stale.size > named.size) "\n  (and ${stale.size - named.size} more)" else "") +
                // Every kind, counted, however long the list is. The examples are
                // capped so the refusal stays readable, and the first live merge
                // showed what that costs on its own: eight bees printed and
                // "(and 292 more)" said nothing about whether the 292 were bees
                // too. That is the difference between one fix and an unknown
                // number of them, and it is the question the operator has to
                // answer before deciding whether to start another two-hour run.
                "\n  every kind, counted:\n" +
                stale.groupingBy { "${it.role.id}: ${it.what}" }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .joinToString("\n") { "    ${it.value} × ${it.key}" } +
                "\n" + placement.dimensions.joinToString("\n") {
                    "  Secondary's ${it.role.id} used to cover ${it.secondary.describeBlocks()} and moved " +
                        placement.offset.describe(it.role)
                },
        )
    }

    /**
     * The cross-check's refusal. It is separate from the one above because the
     * coordinates involved are not stale at all — they moved exactly as they were
     * meant to, and are still wrong, which is a different thing to tell an
     * operator.
     */
    private fun refuseIfAVillagerLostItsPoi() {
        if (orphanedMemories.isEmpty()) return
        throw MigrationRefused(
            "the relocation left ${orphanedMemories.size} villager memor" +
                "${if (orphanedMemories.size == 1) "y" else "ies"} without the point-of-interest record " +
                "that claimed them, so the merge stopped and nothing has been written:\n" +
                orphanedMemories.take(LEFTOVERS_NAMED).joinToString("\n") { "  $it" } +
                if (orphanedMemories.size > LEFTOVERS_NAMED) {
                    "\n  (and ${orphanedMemories.size - LEFTOVERS_NAMED} more)"
                } else {
                    ""
                },
        )
    }

    /** The role [dimensionId] plays in Secondary, or null when it names anywhere else. */
    private fun secondaryRole(dimensionId: String): DimensionRole? =
        DimensionRole.entries.firstOrNull { WorldLayout.SECONDARY.dimensionId(it) == dimensionId }

    /** A block position, by value, so a memory can be looked up among the records. */
    private data class BlockPosition(val x: Int, val y: Int, val z: Int)

    companion object {

        /**
         * Whether [command] spells a place out rather than describing one
         * relative to the block it runs from.
         *
         * Three coordinate tokens in a row, at least one of them absolute — which
         * is vanilla's own coordinate grammar and so catches `/tp @p 85 64 53`
         * without catching `/setblock ~ ~-1 ~ stone`. It is deliberately generous
         * about what else it matches: the cost of a wrong guess is one extra line
         * in a report the operator reads, and never a rewrite.
         */
        fun namesLiteralCoordinates(command: String): Boolean {
            val run = mutableListOf<String>()
            for (token in command.split(' ', '\t', '\n').filter(String::isNotEmpty)) {
                if (!COORDINATE.matches(token)) {
                    run.clear()
                    continue
                }
                run += token
                if (run.size > COORDINATES_PER_PLACE) run.removeAt(0)
                if (run.size == COORDINATES_PER_PLACE && run.any { it.first().isDigit() || it.first() == '-' }) {
                    return true
                }
            }
            return false
        }

        /** One token of vanilla's coordinate grammar: absolute, `~` relative or `^` local. */
        private val COORDINATE = Regex("""[~^]?-?\d+(\.\d+)?|[~^]""")

        private const val COORDINATES_PER_PLACE = 3

        /** How many leftovers a refusal spells out before it stops listing them. */
        private const val LEFTOVERS_NAMED = 8

        // The folders a dimension's chunk data is split across and the `type` each
        // is known by, named once in StagedChunks so that this phase and
        // ChunkCompletion cannot come to disagree about which files they mean.
        private const val TERRAIN = StagedChunks.TERRAIN
        private const val ENTITIES = StagedChunks.ENTITIES
        private const val POI = StagedChunks.POI

        private const val TERRAIN_TYPE = StagedChunks.TERRAIN_TYPE
        private const val ENTITIES_TYPE = StagedChunks.ENTITIES_TYPE
        private const val POI_TYPE = StagedChunks.POI_TYPE

        private const val BLOCKS_PER_CHUNK = 16
        private const val CHUNK_MASK = 0xFFFFFFFFL

        // Every key below is one a 26.2 chunk, entity or point-of-interest codec
        // actually writes; the LEGACY_ ones are what the same value was called
        // before 1.21.5's InlineBlockPosFormatFix, and are still on disk in a chunk
        // that has not been loaded since.
        private const val ID = "id"
        private const val X = "x"
        private const val Y = "y"
        private const val Z = "z"
        private const val POS = "Pos"
        private const val PASSENGERS = "Passengers"
        private const val COMMAND = "Command"

        private const val CHUNK_X = "xPos"
        private const val CHUNK_Z = "zPos"
        private const val ENTITIES_POSITION = "Position"
        private const val ENTITIES_LIST = "Entities"

        private const val STRUCTURES = "structures"
        private const val STRUCTURE_STARTS = "starts"
        private const val STRUCTURE_REFERENCES = "References"
        private const val STRUCTURE_CHILDREN = "Children"
        private const val STRUCTURE_CHUNK_X = "ChunkX"
        private const val STRUCTURE_CHUNK_Z = "ChunkZ"
        private const val STRUCTURE_BOX = "BB"

        private const val POI_SECTIONS = "Sections"
        private const val POI_RECORDS = "Records"

        private const val BRAIN = "Brain"
        private const val BRAIN_MEMORIES = "memories"
        private const val MEMORY_VALUE = "value"

        private const val LODESTONE_TRACKER = "minecraft:lodestone_tracker"
        private const val LODESTONE_TARGET = "target"

        private const val GLOBAL_POS_DIMENSION = "dimension"
        private const val GLOBAL_POS = "pos"

        private const val LEGACY_TILE_X = "TileX"
        private const val LEGACY_TILE_Y = "TileY"
        private const val LEGACY_TILE_Z = "TileZ"

        /** The three memories a villager claims a point of interest with. */
        private val CLAIMED_PLACES = listOf("minecraft:home", "minecraft:job_site", "minecraft:meeting_point")

        /**
         * The arbitrary-NBT escape hatches, whose contents are somebody's stored
         * data rather than the world's geography.
         *
         * [ChunkCompletion]'s own set, deliberately, rather than a second copy of
         * it. This phase exists to prove that one left nothing behind, and it can
         * only do that honestly if the two agree on what was never its to move.
         */
        private val OPAQUE = OPAQUE_NBT

        private const val BLOCK_POS_LENGTH = 3
        private const val CHUNK_POS_LENGTH = 2
        private const val VEC3_LENGTH = 3
        private const val BOUNDING_BOX_LENGTH = 6
    }
}
