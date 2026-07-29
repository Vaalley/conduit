package eu.mctraveler.worlds

import eu.mctraveler.MCTraveler
import eu.mctraveler.persistence.PerWorldBucket
import eu.mctraveler.persistence.PlayerStore
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap

/**
 * The Worlds service: the server's World topology and Travel between Worlds
 * (ADR 0001; spec Implementation Decisions "Worlds" and "Per-World Bucket").
 *
 * The model is N-capable — [all] is an ordered list and every operation works
 * over it — but the product ships with two Worlds: Primary, the vanilla trio,
 * and Secondary, a datapack-defined trio shipped in the mod jar
 * (`data/mctraveler/dimension/secondary{,_nether,_end}.json`).
 *
 * Travel swaps the Per-World Bucket: leaving a World saves Position Memory
 * (position, rotation, dimension role) into [players]; arriving restores the
 * destination's bucket, or lands at the destination's spawn on a first visit.
 * Everything else — inventory, XP, health, hunger, ender chest, advancements,
 * stats — is one pool on the single player entity and rides along untouched.
 */
class Worlds(private val server: MinecraftServer, private val players: PlayerStore) {

    /** Every World, in `/switch` cycle order. */
    val all: List<World> = listOf(
        World("primary", "Primary", mapOf(
            DimensionRole.OVERWORLD to Level.OVERWORLD,
            DimensionRole.NETHER to Level.NETHER,
            DimensionRole.END to Level.END,
        )),
        World("secondary", "Secondary", mapOf(
            DimensionRole.OVERWORLD to dimensionKey("secondary"),
            DimensionRole.NETHER to dimensionKey("secondary_nether"),
            DimensionRole.END to dimensionKey("secondary_end"),
        )),
    )

    fun byId(id: String): World? = all.firstOrNull { it.id == id }

    /** The World [dimension] belongs to, or null (a dimension outside every trio, e.g. a test level). */
    fun worldOf(dimension: ResourceKey<Level>): World? =
        all.firstOrNull { it.roleOf(dimension) != null }

    fun worldOf(player: ServerPlayer): World? = worldOf(player.level().dimension())

    /**
     * Where `/switch` sends [player]: the next World in [all]'s cycle — the
     * other World, with two. From outside every World it falls back to the
     * first, as the Portal fell back to primary when the current server was
     * unknown.
     */
    fun switchDestination(player: ServerPlayer): World {
        val current = worldOf(player) ?: return all.first()
        return all[(all.indexOf(current) + 1) % all.size]
    }

    /**
     * Travel (CONTEXT.md): moves [player] to [destination] near-instantly.
     * Saves the origin World's Per-World Bucket, restores the destination's
     * (spawn on a first visit), and records the destination as the player's
     * last World. Throws if the destination cannot be entered — `/switch`
     * surfaces that as the Portal's failure message.
     */
    fun travel(player: ServerPlayer, destination: World) {
        val origin = worldOf(player)
        if (origin != null) {
            val role = checkNotNull(origin.roleOf(player.level().dimension()))
            players.setBucket(
                player.uuid,
                origin.id,
                PerWorldBucket(role.id, player.x, player.y, player.z, player.yRot, player.xRot),
            )
        }
        place(player, destination)
        players.setLastWorld(player.uuid, destination.id)
    }

    /**
     * Login routing (spec story 3): vanilla already restores the dimension and
     * position the player logged out at; this covers the mismatch case — a
     * lastWorld on record (e.g. freshly imported Portal data) disagreeing with
     * where vanilla put the player — by restoring that World's bucket without
     * saving the login position over the origin World's memory. Always records
     * the World the player ends up in, so lastWorld is set from first login.
     */
    fun handleLogin(player: ServerPlayer) {
        val last = players.lastWorld(player.uuid)?.let(::byId)
        if (last != null && worldOf(player).let { it != null && it != last }) {
            place(player, last)
        }
        val world = worldOf(player) ?: return
        players.setLastWorld(player.uuid, world.id)
    }

    /** Puts [player] where [world] remembers them: their bucket, or the World's spawn on a first visit. */
    private fun place(player: ServerPlayer, world: World) {
        val bucket = players.bucket(player.uuid, world.id)
        if (bucket == null) {
            placeAtSpawn(player, world)
            return
        }
        val role = checkNotNull(DimensionRole.fromId(bucket.dimension)) {
            "player ${player.uuid} has a ${world.id} bucket with unknown dimension \"${bucket.dimension}\""
        }
        teleport(player, level(world, role), bucket.x, bucket.y, bucket.z, bucket.yaw, bucket.pitch)
    }

    /**
     * The destination World's spawn: the shared spawn columns in the World's
     * own overworld, at that terrain's surface (the level data is shared
     * across dimensions, so the Y must come from the destination's terrain).
     */
    private fun placeAtSpawn(player: ServerPlayer, world: World) {
        val level = level(world, DimensionRole.OVERWORLD)
        val spawn = level.respawnData
        val pos = spawn.pos()
        val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.x, pos.z)
        teleport(player, level, pos.x + 0.5, surfaceY.toDouble(), pos.z + 0.5, spawn.yaw(), spawn.pitch())
    }

    private fun teleport(
        player: ServerPlayer,
        level: ServerLevel,
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
    ) {
        check(player.teleportTo(level, x, y, z, emptySet(), yaw, pitch, false)) {
            "teleport into ${level.dimension().identifier()} was rejected"
        }
    }

    private fun level(world: World, role: DimensionRole): ServerLevel {
        val dimension = world.dimension(role)
        return checkNotNull(server.getLevel(dimension)) {
            "the ${dimension.identifier()} dimension is not loaded"
        }
    }

    private companion object {
        fun dimensionKey(path: String): ResourceKey<Level> =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(MCTraveler.MOD_ID, path))
    }
}
