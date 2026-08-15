package eu.mctraveler.worlds

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Unit tier for the merge artifact `/switch` reads back.
 *
 * The file's shape is a contract between two things that never run at the same
 * time — the offline merge writes it, and the server reads it months of uptime
 * later — so it is asserted against raw file content in exactly the form the
 * player sweep emits. The absence cases matter as much as the present one:
 * every unmerged server has no file, and a signpost that threw or lied on one
 * would be worse than the merge it is explaining.
 */
class BankedPositionsTest {

    @TempDir
    lateinit var dir: Path

    private val uuid: UUID = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val stranger: UUID = UUID.fromString("99999999-8888-4777-8666-555555555555")

    private fun file(): Path = dir.resolve(BankedPositions.FILE_NAME)

    private fun positions(): BankedPositions = BankedPositions(file())

    private fun write(players: String) {
        Files.writeString(
            file(),
            """
            {
              "mergedAt": "2026-08-02T00:49:31.123456Z",
              "offset": {"x": 8192, "z": -4096},
              "players": {
            $players
              }
            }

            """.trimIndent(),
        )
    }

    @Test
    fun `a server that was never merged has no file and tells nobody anything`() {
        assertNull(positions().of(uuid))
    }

    @Test
    fun `a recorded player is told the World they knew and the coordinates it is at now`() {
        write("""    "$uuid": {"world":"secondary","dimension":"minecraft:overworld","x":1.5,"y":70.0,"z":2.5}""")
        assertEquals(OtherBase("secondary", "minecraft:overworld", 1.5, 70.0, 2.5), positions().of(uuid))
    }

    @Test
    fun `the World name is the one the player has always been shown`() {
        assertEquals("Secondary", OtherBase("secondary", "minecraft:overworld", 0.0, 0.0, 0.0).worldName)
        assertEquals("Primary", OtherBase("primary", "minecraft:overworld", 0.0, 0.0, 0.0).worldName)
    }

    @Test
    fun `a player the merge recorded nothing for is not in the file`() {
        write("""    "$uuid": {"world":"primary","dimension":"minecraft:the_nether","x":1.5,"y":70.0,"z":2.5}""")
        assertNull(positions().of(stranger))
    }

    @Test
    fun `every recorded player is found, not just the first`() {
        write(
            """    "$uuid": {"world":"primary","dimension":"minecraft:overworld","x":1.5,"y":70.0,"z":2.5},
    "$stranger": {"world":"secondary","dimension":"minecraft:the_end","x":-8.5,"y":60.0,"z":9.5}""",
        )
        val positions = positions()
        assertEquals(OtherBase("primary", "minecraft:overworld", 1.5, 70.0, 2.5), positions.of(uuid))
        assertEquals(OtherBase("secondary", "minecraft:the_end", -8.5, 60.0, 9.5), positions.of(stranger))
    }

    @Test
    fun `a file that will not parse leaves the signpost with nothing to say rather than throwing`() {
        Files.writeString(file(), "{\"players\": {\"not-a-uuid\": ")
        assertNull(positions().of(uuid))
    }

    @Test
    fun `a file with no players object is treated as recording nobody`() {
        Files.writeString(file(), """{"mergedAt":"2026-08-02T00:49:31.123456Z"}""")
        assertNull(positions().of(uuid))
    }

    @Test
    fun `an artifact put in place after the server read is picked up`() {
        val positions = positions()
        assertNull(positions.of(uuid))
        write("""    "$uuid": {"world":"secondary","dimension":"minecraft:overworld","x":1.5,"y":70.0,"z":2.5}""")
        assertEquals(OtherBase("secondary", "minecraft:overworld", 1.5, 70.0, 2.5), positions.of(uuid))
    }
}
