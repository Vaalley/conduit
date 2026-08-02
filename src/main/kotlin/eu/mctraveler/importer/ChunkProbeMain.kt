package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceKey
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo

/**
 * What one chunk actually says about itself, read exactly the way the merge
 * reads it.
 *
 * This exists because of a disagreement the rehearsal turned up: MCA Selector
 * called a chunk finished and [SampledDiff] read its status as empty, and no
 * amount of reasoning from the outside settles which of them is looking at the
 * wrong thing. The merge refuses on that disagreement — correctly, since a chunk
 * that quietly fails to arrive is invisible to every check that only reads the
 * relocated data — so the way forward is to look at the bytes rather than to
 * argue about them.
 *
 * It opens the region file through the same [RegionFile] the merge does, with the
 * same arguments, so that what it prints is what the merge saw and not a second
 * opinion from a different reader.
 *
 * ```
 * ./gradlew chunkProbe --args="<region folder> <chunkX> <chunkZ>"
 * ```
 */
object ChunkProbeMain {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 3) {
            System.err.println("usage: <region folder> <chunkX> <chunkZ>")
            exitProcess(2)
        }
        val folder = Path.of(args[0])
        val at = ChunkPos(args[1].toInt(), args[2].toInt())

        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()

        val file = folder.resolve("r.${at.regionX}.${at.regionZ}.mca")
        println("chunk ${at.x}, ${at.z}  ->  ${file.fileName}")
        if (Files.notExists(file)) {
            println("  the region file does not exist")
            return
        }
        println("  region file size : ${Files.size(file)} bytes")

        val dimension: ResourceKey<Level> = Level.OVERWORLD
        RegionFile(RegionStorageInfo("world", dimension, "chunk"), file, folder, false).use { region ->
            println("  hasChunk         : ${region.hasChunk(at)}")
            val stream = region.getChunkDataInputStream(at)
            if (stream == null) {
                println("  data stream      : null — the header claims it is there but nothing reads back")
                return
            }
            val tag: CompoundTag = stream.use(NbtIo::read)
            report(tag)
        }
    }

    private fun report(tag: CompoundTag) {
        println("  root keys        : ${tag.keySet().sorted()}")
        println("  DataVersion      : ${tag.getIntOr("DataVersion", -1)}")
        println("  Status (root)    : ${tag.getStringOr("Status", "<absent>")}")
        val level = tag.getCompoundOrEmpty("Level")
        if (!level.isEmpty) {
            println("  Level keys       : ${level.keySet().sorted()}")
            println("  Status (Level)   : ${level.getStringOr("Status", "<absent>")}")
        }
        println("  xPos/zPos        : ${tag.getIntOr("xPos", 999999)}, ${tag.getIntOr("zPos", 999999)}")
        println("  sections         : ${tag.getListOrEmpty("sections").size}")
    }
}
