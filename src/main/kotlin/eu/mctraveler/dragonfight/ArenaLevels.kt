package eu.mctraveler.dragonfight

import eu.mctraveler.MCTraveler
import eu.mctraveler.mixin.MinecraftServerAccessor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.storage.DerivedLevelData

object ArenaLevels {
    private const val NAMESPACE = MCTraveler.MOD_ID
    private const val PREFIX = "arena_"

    fun key(owner: UUID): ResourceKey<Level> =
        ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + owner.toString().replace("-", "")),
        )

    fun owner(key: ResourceKey<Level>): UUID? {
        if (key.identifier().namespace != NAMESPACE) return null
        val value = key.identifier().path.removePrefix(PREFIX)
        if (value.length != 32 || !key.identifier().path.startsWith(PREFIX)) return null
        return runCatching {
            UUID.fromString(
                value.chunked(8).let { "${it[0]}-${it[1].take(4)}-${it[1].drop(4)}-${it[2].take(4)}-${it[2].drop(4)}${it[3]}" },
            )
        }.getOrNull()
    }

    fun create(server: MinecraftServer, owner: UUID, borderRadius: Int): ServerLevel {
        val accessor = server as MinecraftServerAccessor
        val endStem = checkNotNull(
            server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).getValue(LevelStem.END),
        )
        val level = ServerLevel(
            server,
            accessor.`mctraveler$executor`(),
            accessor.`mctraveler$storageSource`(),
            DerivedLevelData(server.worldData, server.worldData.overworldData()),
            key(owner),
            LevelStem(endStem.type(), endStem.generator()),
            server.worldData.isDebugWorld,
            BiomeManager.obfuscateSeed(server.getWorldGenSettings().options().seed()),
            emptyList(),
            false,
        )
        accessor.`mctraveler$levels`()[level.dimension()] = level
        level.worldBorder.setAbsoluteMaxSize(server.absoluteMaxWorldSize)
        server.playerList.addWorldborderListener(level)
        level.worldBorder.setCenter(0.0, 0.0)
        level.worldBorder.setSize(2.0 * borderRadius)
        level.worldBorder.setWarningBlocks(5)
        return level
    }

    fun delete(server: MinecraftServer, level: ServerLevel) {
        val accessor = server as MinecraftServerAccessor
        accessor.`mctraveler$levels`().remove(level.dimension())
        try {
            level.close()
        } catch (error: IOException) {
            MCTraveler.LOGGER.warn("Failed to close arena {}", level.dimension(), error)
        }
        val folder = accessor.`mctraveler$storageSource`().getDimensionPath(level.dimension())
        deleteRecursively(folder)
        MCTraveler.LOGGER.info("Deleted dragon fight arena {}", level.dimension())
    }

    fun sweepFolders(server: MinecraftServer) {
        val accessor = server as MinecraftServerAccessor
        val parent = accessor.`mctraveler$storageSource`().getDimensionPath(key(UUID.randomUUID())).parent
        if (Files.notExists(parent)) return
        Files.list(parent).use { paths ->
            paths.filter { it.fileName.toString().startsWith(PREFIX) }.forEach(::deleteRecursively)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (Files.notExists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { candidate ->
                try {
                    Files.deleteIfExists(candidate)
                } catch (error: IOException) {
                    MCTraveler.LOGGER.warn("Failed to delete {}", candidate, error)
                }
            }
        }
    }
}
