package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo

/**
 * [ChunkAudit]'s villager cross-check, run against a dimension nothing has moved.
 *
 * The third live merge passed every coordinate check and then refused over 1222
 * villagers remembering a home, a job site or a meeting point with no
 * point-of-interest record at the other end. That refusal has two possible
 * causes and they call for opposite responses:
 *
 * - the relocation dropped the records, which is a broken map and a merge that
 *   was right to stop; or
 * - the memories were already dangling in Secondary — a bell somebody broke in
 *   2019, a bed mined out from under a villager — and the merge is refusing over
 *   a decade of ordinary wear that has nothing to do with it.
 *
 * Reasoning cannot separate those. Running the identical check over the
 * *unrelocated* source can: whatever it finds here was true before the merge
 * existed. The comparison is only honest if the check is the same one, so the
 * shapes below are [ChunkAudit]'s, deliberately duplicated rather than
 * approximated — `Sections`/`Records`/`pos` for a record, and `Brain.memories`
 * unwrapped through `ExpirableValue`'s `value` for a memory.
 *
 * ```
 * ./gradlew poiCrossCheck --args="<dimension folder>"
 * ```
 */
object PoiCrossCheckMain {

    private val CLAIMED = listOf("minecraft:home", "minecraft:job_site", "minecraft:meeting_point")

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("usage: <dimension folder containing poi/ and entities/>")
            exitProcess(2)
        }
        val root = Path.of(args[0])

        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()

        val places = mutableSetOf<Triple<Int, Int, Int>>()
        var poiFiles = 0
        walk(root.resolve("poi"), "poi") { _, tag ->
            poiFiles++
            for ((_, section) in tag.getCompoundOrEmpty("Sections").entrySet()) {
                if (section !is CompoundTag) continue
                for (record in section.getListOrEmpty("Records")) {
                    if (record !is CompoundTag) continue
                    val at = record.getIntArray("pos").orElse(null) ?: continue
                    if (at.size == 3) places += Triple(at[0], at[1], at[2])
                }
            }
        }
        println("point-of-interest records found : ${places.size}")

        var memories = 0
        var dangling = 0
        val examples = mutableListOf<String>()
        val byPlace = HashMap<Triple<Int, Int, Int>, Int>()
        walk(root.resolve("entities"), "entities") { chunk, tag ->
            for (entity in tag.getListOrEmpty("Entities")) {
                if (entity is CompoundTag) villager(entity, chunk, places) { m, d, note, at ->
                    memories += m
                    dangling += d
                    if (d > 0) {
                        byPlace[at!!] = (byPlace[at] ?: 0) + 1
                        if (examples.size < 8) examples += note
                    }
                }
            }
        }
        println("villager memories checked       : $memories")
        println("dangling (no record at the end) : $dangling")
        println("distinct places they point at   : ${byPlace.size}")
        println()
        println("The same memory remembered by many villagers is one dead village, not many faults:")
        byPlace.entries.sortedByDescending { it.value }.take(6).forEach { (at, n) ->
            println("  $n villagers -> ${at.first}, ${at.second}, ${at.third}")
        }
        println()
        examples.forEach { println("  $it") }
    }

    private fun villager(
        entity: CompoundTag,
        chunk: ChunkPos,
        places: Set<Triple<Int, Int, Int>>,
        report: (Int, Int, String, Triple<Int, Int, Int>?) -> Unit,
    ) {
        val id = entity.getStringOr("id", "?")
        val held = entity.getCompoundOrEmpty("Brain").getCompoundOrEmpty("memories")
        for (memory in CLAIMED) {
            val slot = held.getCompound(memory).orElse(null) ?: continue
            val place = slot.getCompound("value").orElse(slot)
            val at = place.getIntArray("pos").orElse(null) ?: continue
            if (at.size != 3) continue
            val key = Triple(at[0], at[1], at[2])
            if (key in places) {
                report(1, 0, "", null)
            } else {
                report(
                    1,
                    1,
                    "the $id in chunk ${chunk.x}, ${chunk.z} remembers its $memory at " +
                        "${at[0]}, ${at[1]}, ${at[2]}, and no record is there — before any merge",
                    key,
                )
            }
        }
        for (passenger in entity.getListOrEmpty("Passengers")) {
            if (passenger is CompoundTag) villager(passenger, chunk, places, report)
        }
    }

    private fun walk(folder: Path, type: String, visit: (ChunkPos, CompoundTag) -> Unit) {
        if (!Files.isDirectory(folder)) {
            println("no $folder")
            return
        }
        val files = Files.newDirectoryStream(folder, "r.*.mca").use { it.sortedBy(Path::toString) }
        var done = 0
        for (file in files) {
            val at = RegionFilePos.parse(file.fileName.toString()) ?: continue
            done++
            if (done % 1000 == 0) println("  $type: $done of ${files.size}")
            RegionFile(RegionStorageInfo("world", Level.OVERWORLD, type), file, folder, false).use { region ->
                for (localZ in 0 until 32) {
                    for (localX in 0 until 32) {
                        val chunk = ChunkPos(at.x * 32 + localX, at.z * 32 + localZ)
                        if (!region.hasChunk(chunk)) continue
                        region.getChunkDataInputStream(chunk)?.use { visit(chunk, NbtIo.read(it)) }
                    }
                }
            }
        }
    }
}
