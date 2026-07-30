package eu.mctraveler.importer

import eu.mctraveler.persistence.PerWorldBucket
import eu.mctraveler.persistence.RespawnPoint
import eu.mctraveler.worlds.DimensionRole
import net.minecraft.nbt.CompoundTag
import org.slf4j.LoggerFactory

/**
 * The playerdata merge (spec User Story 43).
 *
 * On the Portal a player had one save per backend, and the two only ever met
 * through the switch-time sync of inventory-shaped tags. The merged server
 * keeps exactly one save — the one from the World they were last in, which
 * carries inventory, XP, health, advancement progress and the rest — and the
 * *other* backend's save survives as that World's Per-World Bucket: where they
 * stood, facing where, in which dimension of that trio, and the bed they had
 * set there.
 *
 * Both backends were vanilla servers, so both saves name the vanilla trio.
 * [live] re-points a save at the World it is becoming; [bucket] reads one as
 * Position Memory. A save naming a dimension no backend trio owns is placed in
 * the overworld and warned about — see [roleOf] for why that beats refusing.
 *
 * Save-format differences between the backends' version and the target
 * server's are vanilla's business — the tags touched here are only those that
 * name a dimension — except that a spawn point can still be on disk in its
 * pre-1.21.5 flat form (`SpawnX`/`SpawnDimension`/…) for a player who has not
 * logged in for years, so both spellings are understood.
 */
object PlayerdataImport {

    private val LOGGER = LoggerFactory.getLogger("mctraveler-import")

    /**
     * [tag] as the merged save must key it for a player living in [world]:
     * every dimension the save names is swapped for the dimension playing the
     * same role in that World's trio. For Primary that is the identity.
     */
    fun live(tag: CompoundTag, world: WorldTrio): CompoundTag {
        val live = tag.copy()
        live.putString(DIMENSION, world.dimensionId(roleOf(live.getStringOr(DIMENSION, ""))))
        val respawn = live.getCompound(RESPAWN).orElse(null)
        if (respawn != null) {
            respawn.putString(
                RESPAWN_DIMENSION,
                world.dimensionId(roleOf(respawn.getStringOr(RESPAWN_DIMENSION, ""))),
            )
        }
        val legacySpawnDimension = live.getString(LEGACY_SPAWN_DIMENSION).orElse(null)
        if (legacySpawnDimension != null) {
            live.putString(LEGACY_SPAWN_DIMENSION, world.dimensionId(roleOf(legacySpawnDimension)))
        }
        return live
    }

    /**
     * The Per-World Bucket [tag] — the save from the World the player is *not*
     * in any more — seeds: their Position Memory there, and the respawn point
     * of any bed or anchor they set there.
     */
    fun bucket(tag: CompoundTag): PerWorldBucket {
        val pos = tag.getList("Pos").orElseThrow { missing("Pos") }
        val rotation = tag.getList("Rotation").orElseThrow { missing("Rotation") }
        return PerWorldBucket(
            dimension = roleOf(tag.getStringOr(DIMENSION, "")).id,
            x = pos.getDoubleOr(0, 0.0),
            y = pos.getDoubleOr(1, 0.0),
            z = pos.getDoubleOr(2, 0.0),
            yaw = rotation.getFloatOr(0, 0f),
            pitch = rotation.getFloatOr(1, 0f),
            respawn = respawnPoint(tag),
        )
    }

    /** The bed or anchor [tag] records, in either spelling, or null for a player who set none. */
    private fun respawnPoint(tag: CompoundTag): RespawnPoint? {
        val respawn = tag.getCompound(RESPAWN).orElse(null)
        if (respawn != null) {
            val pos = respawn.getIntArray("pos").orElseThrow { missing("respawn pos") }
            require(pos.size == 3) { "a respawn point has ${pos.size} coordinates" }
            return RespawnPoint(
                dimension = roleOf(respawn.getStringOr(RESPAWN_DIMENSION, "")).id,
                x = pos[0],
                y = pos[1],
                z = pos[2],
                // 1.21.5–1.21.10 stored the single facing as "angle"; later
                // versions split it into yaw and pitch.
                yaw = respawn.getFloatOr("yaw", respawn.getFloatOr("angle", 0f)),
                pitch = respawn.getFloatOr("pitch", 0f),
                forced = respawn.getBooleanOr("forced", false),
            )
        }
        val legacyDimension = tag.getString(LEGACY_SPAWN_DIMENSION).orElse(null) ?: return null
        return RespawnPoint(
            dimension = roleOf(legacyDimension).id,
            x = tag.getIntOr("SpawnX", 0),
            y = tag.getIntOr("SpawnY", 0),
            z = tag.getIntOr("SpawnZ", 0),
            yaw = tag.getFloatOr("SpawnAngle", 0f),
            pitch = 0f,
            forced = tag.getBooleanOr("SpawnForced", false),
        )
    }

    /**
     * The trio role [dimensionId] plays on a backend.
     *
     * A save is not obliged to make sense: the live deployment held one whose
     * `Dimension` was the empty string, and a 13-year-old world can also name a
     * dimension a datapack has since removed. There is no answer for those, and
     * refusing the whole cutover over one player is the wrong trade — the
     * overworld is where vanilla itself puts a player whose dimension it cannot
     * honour, so unplaceable saves land there and are named in the log.
     */
    private fun roleOf(dimensionId: String): DimensionRole =
        WorldLayout.backendRole(dimensionId) ?: DimensionRole.OVERWORLD.also {
            LOGGER.warn(
                "playerdata names dimension \"{}\", which is not part of a backend's trio — placing in the overworld",
                dimensionId,
            )
        }

    private fun missing(what: String) = IllegalArgumentException("playerdata is missing \"$what\"")

    private const val DIMENSION = "Dimension"
    private const val RESPAWN = "respawn"
    private const val RESPAWN_DIMENSION = "dimension"
    private const val LEGACY_SPAWN_DIMENSION = "SpawnDimension"
}
