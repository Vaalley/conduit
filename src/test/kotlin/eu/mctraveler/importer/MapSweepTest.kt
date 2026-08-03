package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.SharedConstants
import net.minecraft.nbt.NbtIo
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The saved maps, and the one thing worse than leaving them alone.
 *
 * These maps were inert before the merge — they name dimensions the server does
 * not register — so the sweep's failure mode is not "does nothing". It is
 * "confidently shows the wrong place": correcting a dimension without moving the
 * centre aims a map at real Primary ground. Every test here is really about the
 * two fields moving together.
 */
class MapSweepTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @TempDir
    lateinit var dir: Path

    private val maps: Path get() = dir.resolve("maps")
    private val staged: Path get() = dir.resolve("staged")

    /** z +802816 in the overworld, which is what the live merge actually used. */
    private val offset = MergeOffset(0, 802_816)

    @Test
    fun `a map naming Secondary's overworld moves with the landmass`() {
        write("0.dat", dimension = "minecraft:last", x = -5504, z = -1792)

        val report = sweep()

        val moved = read("0.dat")
        assertEquals("minecraft:overworld", moved.getStringOr("dimension", ""))
        // Both fields, together. The x offset is zero here, which is exactly the
        // case where forgetting to move a centre looks like it worked.
        assertEquals(-5504, moved.getIntOr("xCenter", 0))
        assertEquals(-1792 + 802_816, moved.getIntOr("zCenter", 0))
        assertEquals(1, report.moved)
    }

    @Test
    fun `a map naming Secondary's nether moves by the nether's own offset`() {
        write("1.dat", dimension = "minecraft:last_nether", x = 64, z = 960)

        sweep()

        val moved = read("1.dat")
        assertEquals("minecraft:the_nether", moved.getStringOr("dimension", ""))
        // Not 802816: the nether moved an eighth as far, so that the portal pairs
        // still line up. A map moved by the overworld's offset would be 700,000
        // blocks out.
        assertEquals(960 + 100_352, moved.getIntOr("zCenter", 0))
    }

    @Test
    fun `the eleven maps that spell it wrong are moved too`() {
        // Whatever wrote these is long gone. The maps are real.
        write("2.dat", dimension = "minecaft:last", x = -8448, z = -6656)

        sweep()

        val moved = read("2.dat")
        assertEquals("minecraft:overworld", moved.getStringOr("dimension", ""))
        assertEquals(-6656 + 802_816, moved.getIntOr("zCenter", 0))
    }

    @Test
    fun `a legacy map under Secondary's old world id moves and keeps its integer`() {
        writeLegacy("3.dat", dimension = 12, x = 9728, z = -12032)

        sweep()

        val moved = read("3.dat")
        // Still an integer, and Primary's. Writing a modern string into a file
        // from before modern strings existed would leave vanilla's own fixer
        // reading an era it does not belong to.
        assertEquals(0, moved.getIntOr("dimension", -99))
        assertTrue(moved.getString("dimension").isEmpty)
        assertEquals(-12032 + 802_816, moved.getIntOr("zCenter", 0))
    }

    @Test
    fun `Primary's own maps are not touched at all`() {
        write("4.dat", dimension = "minecraft:overworld", x = 1920, z = -30464)
        writeLegacy("5.dat", dimension = 0, x = -27065, z = -1106)
        val before = Files.readAllBytes(maps.resolve("4.dat")) to Files.readAllBytes(maps.resolve("5.dat"))

        val report = sweep()

        assertEquals(0, report.moved)
        // Not "rewritten to the same value" — never staged, so never written.
        assertTrue(Files.notExists(staged.resolve("4.dat")))
        assertTrue(Files.notExists(staged.resolve("5.dat")))
        assertTrue(before.first.contentEquals(Files.readAllBytes(maps.resolve("4.dat"))))
        assertTrue(before.second.contentEquals(Files.readAllBytes(maps.resolve("5.dat"))))
    }

    @Test
    fun `the world ids it cannot decide are named rather than quietly skipped`() {
        writeLegacy("6.dat", dimension = 13, x = -20800, z = 448)
        writeLegacy("7.dat", dimension = 14, x = -768, z = -2816)

        val report = sweep()

        assertEquals(0, report.moved)
        // The point of the report: a sweep that only listed what it moved would
        // read the same whether it understood these or merely failed to notice.
        assertTrue(
            report.kinds.any { it.what == "<int 13>" && it.outcome.contains("only likely") },
            report.kinds.toString(),
        )
        assertTrue(report.kinds.any { it.what == "<int 14>" && it.outcome.contains("not worth rewriting") })
    }

    @Test
    fun `an unreadable map is counted and left exactly as it is`() {
        maps.createDirectories()
        Files.write(maps.resolve("8.dat"), byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00))
        val before = Files.readAllBytes(maps.resolve("8.dat"))

        val report = sweep()

        assertEquals(1, report.unreadable)
        assertTrue(before.contentEquals(Files.readAllBytes(maps.resolve("8.dat"))))
        assertTrue(
            report.lines().any { it.contains("already unreadable before the merge") },
            report.lines().toString(),
        )
    }

    @Test
    fun `reporting without staging changes nothing on disk`() {
        write("9.dat", dimension = "minecraft:last", x = -5504, z = -1792)
        val before = Files.readAllBytes(maps.resolve("9.dat"))

        val report = MapSweep(maps, offset).sweep()

        assertEquals(1, report.moved)
        assertTrue(before.contentEquals(Files.readAllBytes(maps.resolve("9.dat"))))
    }

    // ---- the save ----------------------------------------------------------

    private fun sweep(): MapSweepReport {
        staged.createDirectories()
        val report = MapSweep(maps, offset) { staged.resolve(it.fileName) }.sweep()
        // The caller commits; here that is a move, which is what MergeStaging does.
        Files.newDirectoryStream(staged).use { staged ->
            staged.forEach { Files.move(it, maps.resolve(it.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        }
        return report
    }

    private fun write(name: String, dimension: String, x: Int, z: Int) = save(
        name,
        CompoundTag().apply {
            putString("dimension", dimension)
            putInt("xCenter", x)
            putInt("zCenter", z)
            putByte("scale", 1)
        },
    )

    /** A map from before 1.16, when the dimension was a number and lived at the root. */
    private fun writeLegacy(name: String, dimension: Int, x: Int, z: Int) = save(
        name,
        CompoundTag().apply {
            putInt("dimension", dimension)
            putInt("xCenter", x)
            putInt("zCenter", z)
            putByte("scale", 1)
        },
    )

    private fun save(name: String, data: CompoundTag) {
        maps.createDirectories()
        NbtIo.writeCompressed(CompoundTag().apply { put("data", data) }, maps.resolve(name))
    }

    private fun read(name: String): CompoundTag =
        NbtIo.readCompressed(maps.resolve(name), NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data")
}
