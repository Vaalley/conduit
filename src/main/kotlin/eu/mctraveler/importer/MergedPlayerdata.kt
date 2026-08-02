package eu.mctraveler.importer

import eu.mctraveler.persistence.PerWorldBucket
import eu.mctraveler.persistence.RespawnPoint
import eu.mctraveler.worlds.DimensionRole
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag

/**
 * Every place a player remembers, rewritten from Secondary's coordinates to the
 * merged map's (merge spec, "Data sweep"; ticket 06).
 *
 * A player carries far more geography than their position. Their save records
 * the dimension they are standing in and the position itself; the bed or anchor
 * they respawn at; where they last died, which is what a recovery compass reads;
 * the point they entered the nether from; the vehicle they logged out inside,
 * which has a position of its own and may be carrying others; the ender pearls
 * still in flight; a parrot on each shoulder; and every lodestone compass in
 * their inventory or ender chest, nested arbitrarily deep inside shulker boxes
 * inside other containers. Their player record adds the World they were last in
 * and a Per-World Bucket per World, each with its own position and respawn point.
 *
 * **Which side of the merge a place is on is decided by the dimension that place
 * itself names, never by the player holding it.** That is the whole of how the
 * two mirrors stay straight, and it is not a simplification: one save routinely
 * names both Worlds at once. A player standing in Primary can hold a compass
 * bound to a Secondary lodestone and a death location in Secondary from before
 * they last Travelled; a player standing in Secondary the exact mirror. So there
 * is no "this is a Secondary player" decision anywhere here — each place is asked
 * which dimension it is in and moves, or does not, on its own answer, and both
 * mirrors are one path.
 *
 * The places a save records with *no* dimension of their own — the player's
 * position, the vehicle under them, the block they are asleep in, the raid omen
 * they are carrying — are in whichever dimension the save's `Dimension` names,
 * and take that dimension's answer instead.
 *
 * Secondary's End is left exactly as it is. It is discarded rather than
 * relocated, so it has no offset at all, and re-pointing a place in it at
 * Primary's End would invent somewhere the player has never been. What becomes
 * of a player anchored there is the End gate's decision, not the sweep's.
 *
 * The field names are 26.2's, read off the save codecs in `ServerPlayer`,
 * `Player`, `LivingEntity` and `Entity` rather than off a list of what a player
 * is thought to carry. Where 1.21.5's `InlineBlockPosFormatFix` renamed one, both
 * spellings are understood: playerdata is only upgraded when it is *loaded*, so a
 * player who has not logged in since the Portal cutover still has the older
 * spelling sitting on disk.
 */
object MergedPlayerdata {

    /**
     * [tag] — a save this server already keeps — with every place it names in
     * Secondary rewritten to its merged one.
     *
     * Always a copy, so the caller can compare it against what it read and tell a
     * player the merge actually moved from one it merely looked at — which is the
     * difference between a save that gets rewritten and one that is left alone.
     */
    fun merged(tag: CompoundTag, offset: MergeOffset): CompoundTag =
        merged(tag, offset, ::secondaryRole)

    /**
     * [tag] — a save taken out of Secondary's half of the Portal cutover's
     * quarantine — moved exactly as the sweep moves a save this server already
     * keeps (ticket 10; merge spec, "The claim path").
     *
     * The quarantine is the one place the rule the rest of this file is built on
     * does not hold. A quarantined save was written by a Portal-era backend, and
     * both backends were plain vanilla servers, so every place in it names the
     * *vanilla* trio however deep in Secondary it actually is — [secondaryRole]
     * would recognise none of them and the save would land, silently, at
     * Secondary's old coordinates in the middle of Primary's map.
     *
     * So which World a place is in is answered here by the quarantine directory
     * the save came out of rather than by the place itself, and that is sound for
     * exactly the reason it is unsound everywhere else: a save that has not been
     * opened since before the cutover cannot name both Worlds at once. One
     * backend wrote all of it, and if that backend was Secondary's then every
     * place in it — the position, the bed, the death location, the compasses —
     * is a Secondary place. [OrphanedSaveClaim] never calls this for a save out
     * of Primary's quarantine, whose owner was never anywhere that moved.
     */
    fun mergedFromSecondarysQuarantine(tag: CompoundTag, offset: MergeOffset): CompoundTag =
        merged(tag, offset, WorldLayout::backendRole)

    /**
     * The whole transform, over a save whose Secondary places are spelled by
     * [inSecondary] — the mod's own dimension ids for a save this server keeps,
     * the vanilla ones for a save still in the quarantine. Everything downstream
     * of that one question is shared, so the offset is applied by one statement
     * whichever door the save came in through.
     */
    private fun merged(
        tag: CompoundTag,
        offset: MergeOffset,
        inSecondary: (String) -> DimensionRole?,
    ): CompoundTag {
        val save = tag.copy()
        mergeGlobalPositions(save, offset, inSecondary)
        mergeLegacyRespawn(save, offset, inSecondary)
        mergeEnderPearls(save, offset, inSecondary)
        mergeWhereTheyStand(save, offset, inSecondary)
        return save
    }

    /**
     * [bucket] — a Per-World Bucket the *caller* has established belongs to
     * Secondary — with its Position Memory and its respawn point moved. A Primary
     * bucket is never passed here: nothing in Primary moved, and a bucket that
     * came out changed would be the trap this ticket is named for.
     */
    fun merged(bucket: PerWorldBucket, offset: MergeOffset): PerWorldBucket {
        val role = roleOf(bucket.dimension, "a Per-World Bucket")
        val moved = if (role == DimensionRole.END) {
            bucket
        } else {
            bucket.copy(x = offset.mergedX(bucket.x, role), z = offset.mergedZ(bucket.z, role))
        }
        return moved.copy(respawn = moved.respawn?.let { merged(it, offset) })
    }

    /** [point] — a Secondary respawn point — moved to where that bed stands now. */
    private fun merged(point: RespawnPoint, offset: MergeOffset): RespawnPoint {
        val role = roleOf(point.dimension, "a respawn point")
        if (role == DimensionRole.END) return point
        return point.copy(x = offset.mergedX(point.x, role), z = offset.mergedZ(point.z, role))
    }

    /**
     * A bucket names its dimension by trio role, not by dimension id. An id no
     * role answers for is corrupt data, and the merge stops over it rather than
     * leaving a Secondary position silently where it was — the same stance
     * [eu.mctraveler.persistence.JsonPlayerStore] takes on a record it cannot read.
     */
    private fun roleOf(dimension: String, what: String): DimensionRole =
        DimensionRole.fromId(dimension)
            ?: throw IllegalArgumentException("$what names dimension role \"$dimension\", which is not a role")

    // ---- the places that name their own dimension ---------------------------

    /**
     * Every global position anywhere in [tag], at any depth: a compound naming a
     * dimension beside a block position is vanilla's `GlobalPos`, and that shape
     * is how the save spells the respawn point, the last death location, every
     * lodestone compass's target, and the brain memories of a mob logged out in
     * the same boat.
     *
     * Walking for the shape rather than for a list of key paths is what makes
     * "including inside nested containers" true by construction: a compass is
     * reached the same way whether it is in the hotbar, inside a shulker box
     * inside a chest boat, or in a bundle in the ender chest, and a component
     * added by a future version is reached without this code being told about it.
     *
     * It is a shape and not a heuristic. A bare int array of three would also
     * match a hundred harmless things — and a uuid is an int array of four, one
     * element away — so the dimension string is the discriminator, and it has to
     * name one of *Secondary's* three dimensions before anything is touched.
     */
    private fun mergeGlobalPositions(
        tag: CompoundTag,
        offset: MergeOffset,
        inSecondary: (String) -> DimensionRole?,
    ) {
        mergeGlobalPosition(tag, offset, inSecondary)
        for ((_, value) in tag.entrySet()) {
            when (value) {
                is CompoundTag -> mergeGlobalPositions(value, offset, inSecondary)
                is ListTag -> value.forEach {
                    if (it is CompoundTag) mergeGlobalPositions(it, offset, inSecondary)
                }
                else -> Unit
            }
        }
    }

    private fun mergeGlobalPosition(
        tag: CompoundTag,
        offset: MergeOffset,
        inSecondary: (String) -> DimensionRole?,
    ) {
        val role = inSecondary(tag.getStringOr(GLOBAL_POS_DIMENSION, "")) ?: return
        if (role == DimensionRole.END) return
        val pos = tag.getIntArray(GLOBAL_POS).orElse(null) ?: return
        if (pos.size != BLOCK_POS_LENGTH) return
        tag.putString(GLOBAL_POS_DIMENSION, WorldLayout.PRIMARY.dimensionId(role))
        tag.putIntArray(GLOBAL_POS, movedBlockPos(pos, offset, role))
    }

    /**
     * The spawn point as it was spelled before 1.21.5 folded it into `respawn`.
     * Still on disk for a player who has not logged in for years, and carried
     * across verbatim by the Portal cutover — [PlayerdataImport] met the same
     * form and understands both spellings for the same reason.
     */
    private fun mergeLegacyRespawn(
        save: CompoundTag,
        offset: MergeOffset,
        inSecondary: (String) -> DimensionRole?,
    ) {
        val role = inSecondary(save.getStringOr(LEGACY_SPAWN_DIMENSION, "")) ?: return
        if (role == DimensionRole.END) return
        save.putString(LEGACY_SPAWN_DIMENSION, WorldLayout.PRIMARY.dimensionId(role))
        save.putInt(LEGACY_SPAWN_X, offset.mergedX(save.getIntOr(LEGACY_SPAWN_X, 0), role))
        save.putInt(LEGACY_SPAWN_Z, offset.mergedZ(save.getIntOr(LEGACY_SPAWN_Z, 0), role))
    }

    /**
     * Ender pearls the player threw and that are still in the air, each saved as
     * a whole entity beside the dimension it is flying through — the one place a
     * save records a dimension under a name of its own rather than as a
     * `GlobalPos`. A pearl left over Secondary has to arrive where its landmass
     * did, or it teleports its owner into the ground it used to be above.
     */
    private fun mergeEnderPearls(
        save: CompoundTag,
        offset: MergeOffset,
        inSecondary: (String) -> DimensionRole?,
    ) {
        for (pearl in save.getListOrEmpty(ENDER_PEARLS)) {
            if (pearl !is CompoundTag) continue
            val role = inSecondary(pearl.getStringOr(ENDER_PEARL_DIMENSION, "")) ?: continue
            if (role == DimensionRole.END) continue
            pearl.putString(ENDER_PEARL_DIMENSION, WorldLayout.PRIMARY.dimensionId(role))
            mergeEntity(pearl, offset, role)
        }
    }

    // ---- the places that take the save's own dimension ----------------------

    /**
     * Where the player is standing, and everything positioned relative to that
     * rather than to a dimension of its own.
     */
    private fun mergeWhereTheyStand(
        save: CompoundTag,
        offset: MergeOffset,
        inSecondary: (String) -> DimensionRole?,
    ) {
        val role = inSecondary(save.getStringOr(DIMENSION, "")) ?: return
        if (role == DimensionRole.END) return
        save.putString(DIMENSION, WorldLayout.PRIMARY.dimensionId(role))
        mergeEntity(save, offset, role)
        mergeBlockPos(save, RAID_OMEN_POSITION, offset, role)
        mergeVec3(save, LAST_EXPLOSION_IMPACT_POS, offset, role)
        for (shoulder in SHOULDER_ENTITIES) {
            save.getCompound(shoulder).orElse(null)?.let { mergeEntity(it, offset, role) }
        }
        // The root vehicle is saved whole, passengers and all, under a wrapper
        // whose other field is the uuid of the seat the player was in — a uuid,
        // not a place, and so nothing to move.
        save.getCompoundOrEmpty(ROOT_VEHICLE).getCompound(ROOT_VEHICLE_ENTITY).orElse(null)
            ?.let { mergeEntity(it, offset, role) }

        // The nether entry point is an *overworld* position: vanilla records where
        // the player stood as they stepped through, and the advancement that reads
        // it measures overworld distance. So it takes the overworld's shift even
        // for a player who is standing in the nether, which is exactly when it is
        // set. A player who has since Travelled carries a stale one from the other
        // World and there is no way to tell — nothing records which — so it goes
        // by the save's own dimension. The cost of being wrong is one advancement
        // measured from the wrong place.
        mergeVec3(save, ENTERED_NETHER_POS, offset, DimensionRole.OVERWORLD)
        mergeLegacyNetherEntry(save, offset)
    }

    /**
     * One entity tag — the player, the boat they logged out in, a parrot on a
     * shoulder, a pearl in flight — and everything riding it, to any depth.
     *
     * `Motion` and `Rotation` stay exactly as they are: a velocity and a facing
     * are not places, and a player must arrive facing the way they left.
     */
    private fun mergeEntity(entity: CompoundTag, offset: MergeOffset, role: DimensionRole) {
        mergeVec3(entity, POS, offset, role)
        mergeBlockPos(entity, SLEEPING_POS, offset, role)
        mergeLegacySleepingPos(entity, offset, role)
        mergeVec3(entity, CURRENT_EXPLOSION_IMPACT_POS, offset, role)
        // A boat can be tied to a fence, and then the knot is a block position
        // rather than the uuid of a mob — vanilla stores either under this key.
        mergeBlockPos(entity, LEASH, offset, role)
        for (passenger in entity.getListOrEmpty(PASSENGERS)) {
            if (passenger is CompoundTag) mergeEntity(passenger, offset, role)
        }
    }

    /** The nether entry point as it was spelled before 1.21.5 gave it a Vec3 codec. */
    private fun mergeLegacyNetherEntry(save: CompoundTag, offset: MergeOffset) {
        val entry = save.getCompound(LEGACY_ENTERED_NETHER_POS).orElse(null) ?: return
        entry.putDouble("x", offset.mergedX(entry.getDoubleOr("x", 0.0), DimensionRole.OVERWORLD))
        entry.putDouble("z", offset.mergedZ(entry.getDoubleOr("z", 0.0), DimensionRole.OVERWORLD))
    }

    /** The bed a sleeping player is in, as it was spelled before 1.21.5. */
    private fun mergeLegacySleepingPos(entity: CompoundTag, offset: MergeOffset, role: DimensionRole) {
        if (!entity.contains(LEGACY_SLEEPING_X)) return
        entity.putInt(LEGACY_SLEEPING_X, offset.mergedX(entity.getIntOr(LEGACY_SLEEPING_X, 0), role))
        entity.putInt(LEGACY_SLEEPING_Z, offset.mergedZ(entity.getIntOr(LEGACY_SLEEPING_Z, 0), role))
    }

    // ---- the two shapes a position comes in ---------------------------------

    /** A `Vec3.CODEC` position: three doubles, moved to the fraction of a block. */
    private fun mergeVec3(tag: CompoundTag, key: String, offset: MergeOffset, role: DimensionRole) {
        val pos = tag.getList(key).orElse(null) ?: return
        if (pos.size != VEC3_LENGTH) return
        tag.put(
            key,
            ListTag().apply {
                add(DoubleTag.valueOf(offset.mergedX(pos.getDoubleOr(0, 0.0), role)))
                add(DoubleTag.valueOf(pos.getDoubleOr(1, 0.0)))
                add(DoubleTag.valueOf(offset.mergedZ(pos.getDoubleOr(2, 0.0), role)))
            },
        )
    }

    /** A `BlockPos.CODEC` position: three ints. Four would be a uuid, and is left alone. */
    private fun mergeBlockPos(tag: CompoundTag, key: String, offset: MergeOffset, role: DimensionRole) {
        val pos = tag.getIntArray(key).orElse(null) ?: return
        if (pos.size != BLOCK_POS_LENGTH) return
        tag.putIntArray(key, movedBlockPos(pos, offset, role))
    }

    private fun movedBlockPos(pos: IntArray, offset: MergeOffset, role: DimensionRole): IntArray =
        intArrayOf(offset.mergedX(pos[0], role), pos[1], offset.mergedZ(pos[2], role))

    /**
     * The role [dimensionId] plays in Secondary as a save this server keeps
     * spells it, or null — it names one of Primary's dimensions, the Embassies,
     * or something no World owns, all of which the merge leaves exactly where
     * they are.
     *
     * A save still in the Portal cutover's quarantine spells the same places the
     * other way; see [mergedFromSecondarysQuarantine].
     */
    private fun secondaryRole(dimensionId: String): DimensionRole? =
        DimensionRole.entries.firstOrNull { WorldLayout.SECONDARY.dimensionId(it) == dimensionId }

    // Every key below is one a 26.2 save codec actually writes; the LEGACY_ ones
    // are what the same value was called before 1.21.5's InlineBlockPosFormatFix.
    private const val DIMENSION = "Dimension"
    private const val POS = "Pos"
    private const val PASSENGERS = "Passengers"
    private const val LEASH = "leash"
    private const val SLEEPING_POS = "sleeping_pos"
    private const val RAID_OMEN_POSITION = "raid_omen_position"
    private const val ENTERED_NETHER_POS = "entered_nether_pos"
    private const val LAST_EXPLOSION_IMPACT_POS = "last_explosion_impact_pos"
    private const val CURRENT_EXPLOSION_IMPACT_POS = "current_explosion_impact_pos"
    private const val ROOT_VEHICLE = "RootVehicle"
    private const val ROOT_VEHICLE_ENTITY = "Entity"
    private const val ENDER_PEARLS = "ender_pearls"
    private const val ENDER_PEARL_DIMENSION = "ender_pearl_dimension"
    private val SHOULDER_ENTITIES = listOf("ShoulderEntityLeft", "ShoulderEntityRight")

    private const val GLOBAL_POS_DIMENSION = "dimension"
    private const val GLOBAL_POS = "pos"

    private const val LEGACY_SPAWN_DIMENSION = "SpawnDimension"
    private const val LEGACY_SPAWN_X = "SpawnX"
    private const val LEGACY_SPAWN_Z = "SpawnZ"
    private const val LEGACY_ENTERED_NETHER_POS = "enteredNetherPosition"
    private const val LEGACY_SLEEPING_X = "SleepingX"
    private const val LEGACY_SLEEPING_Z = "SleepingZ"

    private const val VEC3_LENGTH = 3
    private const val BLOCK_POS_LENGTH = 3
}
