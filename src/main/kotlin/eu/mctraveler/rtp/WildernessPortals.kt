package eu.mctraveler.rtp

import com.google.gson.GsonBuilder
import eu.mctraveler.MCTraveler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

/**
 * The physical wilderness portals: a cuboid of water, kept from flowing out of its box and
 * sparkling with enchanting-table glyphs, that sends whoever walks into it to a random place
 * in the wilderness (the overworld, or the End when the portal stands in the End).
 *
 * Portals live in `<server>/mctraveler/wilderness-portals.json`.
 */
object WildernessPortals {

    class Portal(val id: String, val dimension: String, val box: PortalBox)

    private class FileFormat(val portals: List<Portal>)

    /** The most blocks of water a single portal may be made of. */
    const val MAX_VOLUME = 20_000L

    /** How often the portals sparkle, in ticks. */
    private const val PARTICLE_INTERVAL = 5

    /** How far a player may be, in blocks, for a portal to bother sparkling. */
    private const val PARTICLE_RANGE_SQ = 64.0 * 64.0

    private const val PARTICLES_PER_BURST = 12

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var portals: List<Portal> = emptyList()
    private var file: Path? = null

    /** Players currently standing in a portal, so a stay triggers one teleport and not one per tick. */
    private val inside = HashSet<UUID>()

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server -> load(server.serverDirectory.resolve("mctraveler").resolve("wilderness-portals.json")) }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            portals = emptyList()
            inside.clear()
        }
        ServerTickEvents.END_SERVER_TICK.register(::tick)
    }

    fun all(): List<Portal> = portals

    private fun load(source: Path) {
        file = source
        portals = try {
            if (Files.notExists(source)) emptyList() else gson.fromJson(Files.readString(source), FileFormat::class.java)?.portals.orEmpty()
        } catch (failure: Exception) {
            MCTraveler.LOGGER.error("Failed to load wilderness portals from {}: {}", source, failure.message, failure)
            emptyList()
        }
    }

    private fun save() {
        val target = file ?: return
        try {
            Files.createDirectories(target.parent)
            val staging = target.resolveSibling("${target.fileName}.tmp")
            Files.writeString(staging, gson.toJson(FileFormat(portals)))
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            MCTraveler.LOGGER.error("Failed to save wilderness portals to {}: {}", target, failure.message, failure)
        }
    }

    private fun dimensionId(level: Level): String = level.dimension().identifier().toString()

    fun portalsIn(level: Level): List<Portal> = portals.filter { it.dimension == dimensionId(level) }

    /** The portal whose box contains [pos], if any. */
    fun at(level: Level, pos: BlockPos): Portal? = portalsIn(level).firstOrNull { it.box.contains(pos) }

    /** The nearest portal to [player] in their dimension, within [range] blocks. */
    fun nearest(player: ServerPlayer, range: Double): Portal? =
        portalsIn(player.level())
            .map { it to it.box.distanceSq(player.x, player.y, player.z) }
            .filter { it.second <= range * range }
            .minByOrNull { it.second }
            ?.first

    /** Whether [box] touches a portal that already stands in [level]. */
    fun overlaps(level: Level, box: PortalBox): Boolean = portalsIn(level).any { it.box.intersects(box) }

    /** Fills [box] with water and records the portal. */
    fun create(level: ServerLevel, box: PortalBox): Portal {
        val water = Blocks.WATER.defaultBlockState()
        val pos = BlockPos.MutableBlockPos()
        for (x in box.minX..box.maxX) for (y in box.minY..box.maxY) for (z in box.minZ..box.maxZ) {
            level.setBlock(pos.set(x, y, z), water, 2)
        }
        val portal = Portal(UUID.randomUUID().toString(), dimensionId(level), box)
        portals = portals + portal
        save()
        return portal
    }

    /** Drains [portal] back to air and forgets it. */
    fun remove(level: ServerLevel, portal: Portal) {
        portals = portals.filter { it !== portal }
        val air = Blocks.AIR.defaultBlockState()
        val box = portal.box
        val pos = BlockPos.MutableBlockPos()
        for (x in box.minX..box.maxX) for (y in box.minY..box.maxY) for (z in box.minZ..box.maxZ) {
            pos.set(x, y, z)
            if (level.getBlockState(pos).`is`(Blocks.WATER)) level.setBlock(pos, air, 2)
        }
        save()
    }

    /** Whether water may not flow from [from] to [to]: it would leave a portal's box. */
    @JvmStatic
    fun blocksSpread(level: Level, from: BlockPos, to: BlockPos): Boolean {
        if (portals.isEmpty()) return false
        val source = at(level, from) ?: return false
        return !source.box.contains(to)
    }

    private fun tick(server: MinecraftServer) {
        if (portals.isEmpty()) {
            if (inside.isNotEmpty()) inside.clear()
            return
        }
        for (player in server.playerList.players) {
            if (player.isSpectator) {
                inside.remove(player.uuid)
                continue
            }
            val portal = portalsIn(player.level()).firstOrNull { it.box.toAabb().intersects(player.boundingBox) }
            if (portal == null) {
                inside.remove(player.uuid)
            } else if (inside.add(player.uuid)) {
                try {
                    RtpFeature.enterPortal(player)
                } catch (failure: Exception) {
                    MCTraveler.LOGGER.warn("Wilderness portal failed for ${player.gameProfile.name}", failure)
                }
            }
        }
        if (server.tickCount % PARTICLE_INTERVAL == 0) sparkle(server)
    }

    private fun sparkle(server: MinecraftServer) {
        for (portal in portals) {
            val level = server.getAllLevels().firstOrNull { dimensionId(it) == portal.dimension } ?: continue
            val box = portal.box
            val cx = (box.minX + box.maxX + 1) / 2.0
            val cy = (box.minY + box.maxY + 1) / 2.0
            val cz = (box.minZ + box.maxZ + 1) / 2.0
            if (level.players().none { it.distanceToSqr(cx, cy, cz) < PARTICLE_RANGE_SQ }) continue
            level.sendParticles(
                ParticleTypes.ENCHANT,
                cx, cy, cz, PARTICLES_PER_BURST,
                box.sizeX / 2.0, box.sizeY / 2.0, box.sizeZ / 2.0, 0.6,
            )
        }
    }
}
