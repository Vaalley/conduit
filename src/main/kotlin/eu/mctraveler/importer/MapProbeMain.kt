package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.Tag
import net.minecraft.server.Bootstrap

/**
 * What the saved maps actually say, read the way the server reads them.
 *
 * A filled map does not carry its own coordinates: the item holds a `map_id` and
 * the picture, the centre and the dimension live in a level-wide
 * `data/minecraft/maps/<id>.dat`. That data is not chunk data and not inside
 * Secondary's dimension folder, so nothing in the merge touched it — which
 * leaves 7,372 files whose contents nobody has looked at.
 *
 * A byte-scan of them gave answers that could not all be true (a dimension of
 * `12`, a `minecraft:last` with a letter missing), so this reads them properly
 * before anything rewrites them. **The point is to find out what is actually
 * there, including what turns out to be undecidable**, because a sweep that
 * silently skips a format it did not expect would leave the job half done in a
 * way nobody would notice.
 *
 * The two questions it has to answer:
 *
 * - **Which maps show Secondary?** Modern saves name the dimension as a string,
 *   and Secondary's is the Portal's old backend id rather than anything this
 *   server registers.
 * - **Can the legacy ones be told apart at all?** Before 1.16 the field was a
 *   *number*, and on a Bukkit server that number was the environment — 0 for any
 *   overworld — not which world. If Primary's maps and Secondary's maps both say
 *   0, no rewrite can separate them by dimension, and the centre is the only
 *   evidence left.
 *
 * ```
 * ./gradlew mapProbe --args="<maps folder>"
 * ```
 */
object MapProbeMain {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("usage: <world>/data/minecraft/maps")
            exitProcess(2)
        }
        val folder = Path.of(args[0])

        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()

        val files = Files.newDirectoryStream(folder, "*.dat").use { it.sortedBy(Path::toString) }
        println("map files: ${files.size}")

        var unreadable = 0
        val kinds = LinkedHashMap<String, Kind>()
        val versions = HashMap<Int, Int>()

        for (file in files) {
            val root = try {
                NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            } catch (failure: Exception) {
                unreadable++
                continue
            }
            versions[root.getIntOr("DataVersion", -1)] =
                (versions[root.getIntOr("DataVersion", -1)] ?: 0) + 1
            // 1.16 and later nest everything under `data`; older saves put it at
            // the root. Read both rather than assuming which era this file is from.
            val data = root.getCompoundOrEmpty("data").takeIf { !it.isEmpty } ?: root
            val dimension = data.get("dimension")
            kinds.getOrPut(describe(dimension)) { Kind() }.add(
                data.getIntOr("xCenter", Int.MIN_VALUE),
                data.getIntOr("zCenter", Int.MIN_VALUE),
                file.fileName.toString(),
            )
        }

        println("unreadable (corrupt)     : $unreadable")
        println("DataVersions             : ${versions.entries.sortedBy { it.key }.joinToString { "${it.key}×${it.value}" }}")
        println()
        println("%-28s %7s  %s".format("dimension field", "files", "centre bounding box (x, z)"))
        for ((what, kind) in kinds.entries.sortedByDescending { it.value.count }) {
            println("%-28s %7d  %s".format(what, kind.count, kind.box()))
        }
        println()
        println("Secondary ran a ±50,000 border, so a centre outside that cannot be a Secondary map.")
        for ((what, kind) in kinds.entries.sortedByDescending { it.value.count }) {
            if (kind.count < 20) continue
            println(
                "  %-26s inside ±50,000: %d of %d   examples: %s".format(
                    what, kind.insideBorder, kind.count, kind.examples.take(3).joinToString(),
                ),
            )
        }
    }

    /** The field as it really is — its type as well as its value. */
    private fun describe(tag: Tag?): String = when {
        tag == null -> "<absent>"
        tag.asString().isPresent -> tag.asString().get()
        tag.asInt().isPresent -> "<int ${tag.asInt().get()}>"
        else -> "<${tag.type.name}>"
    }

    private class Kind {
        var count = 0
        var insideBorder = 0
        private var minX = Int.MAX_VALUE
        private var maxX = Int.MIN_VALUE
        private var minZ = Int.MAX_VALUE
        private var maxZ = Int.MIN_VALUE
        val examples = mutableListOf<String>()

        fun add(x: Int, z: Int, name: String) {
            count++
            if (x == Int.MIN_VALUE || z == Int.MIN_VALUE) return
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
            if (kotlin.math.abs(x) <= BORDER && kotlin.math.abs(z) <= BORDER) insideBorder++
            if (examples.size < 3) examples += "$name($x,$z)"
        }

        fun box(): String =
            if (minX == Int.MAX_VALUE) "no centre recorded" else "x $minX…$maxX  z $minZ…$maxZ"
    }

    private const val BORDER = 50_000
}
