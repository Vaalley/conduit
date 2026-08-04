package eu.mctraveler.gametest

import eu.mctraveler.importer.ImportPlan
import eu.mctraveler.importer.OfflineUuid
import eu.mctraveler.importer.PortalImport
import eu.mctraveler.region.RegionService
import eu.mctraveler.region.RegionWorlds
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.SharedConstants
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource

/**
 * The cutover, against a running server (spec User Stories 43–44): a migrated
 * player logs in through the real login path where the Portal left them, and a
 * migrated region resolves against a dimension the booted server actually has.
 *
 * The importer's own transforms are covered by the unit tier; what only a
 * server can show is that what it wrote is what the live code reads.
 *
 * **Both cases are about the Primary half of a migration, and that is the whole
 * of what this seam can still say.** They used to be about the Secondary half —
 * a player logging into `mctraveler:secondary` and Travelling back to the
 * Per-World Bucket the migration seeded, and a Region recorded under
 * `last_nether` — because that was the half a single-server port had to prove it
 * had reproduced. Neither is assertable now: `mergeWorlds` relocates Secondary's
 * chunk data into Primary's dimensions and this build then removes those
 * dimensions, so a booted server has nowhere to put such a player and no
 * dimension for such a Region to name. What the merge writes, and how a booted
 * server reads it back, is ticket 11's merge gametest rather than this one's —
 * `migrate` runs long before the merge, and its Secondary output is the merge's
 * input, not this server's.
 */
class MigrationGameTest {

    private val migrantUuid: UUID = UUID.fromString("6f0e8d3a-1c2b-4d5e-8f90-a1b2c3d4e5f6")
    private val migrantName = "Migrant"

    @GameTest(maxTicks = 600)
    fun aMigratedPlayerLogsInWhereThePortalLeftThem(helper: GameTestHelper) {
        val server = helper.level.server
        val migrated = migrate(server)
        try {
            // Loom reuses the gametest run directory, and the migrant plays on
            // (and is saved) every run — so the migrated state is laid down
            // fresh each time rather than found half-played.
            Files.copy(
                migrated.resolve("world/playerdata/$migrantUuid.dat"),
                server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve("$migrantUuid.dat"),
                StandardCopyOption.REPLACE_EXISTING,
            )
            val records = server.serverDirectory.resolve("mctraveler/players")
            Files.createDirectories(records)
            Files.copy(
                migrated.resolve("mctraveler/players/$migrantUuid.json"),
                records.resolve("$migrantUuid.json"),
                StandardCopyOption.REPLACE_EXISTING,
            )

            val player = TestPlayers.login(server, migrantName, migrantUuid)
            try {
                helper.assertValueEqual(
                    player.level().dimension(),
                    Level.NETHER,
                    "the dimension a migrated player logs into",
                )
                helper.assertValueEqual(
                    listOf(player.x, player.y, player.z),
                    listOf(10.5, 70.0, -20.5),
                    "the position a migrated player logs into",
                )
            } finally {
                TestPlayers.logout(player)
            }
        } finally {
            deleteRecursively(migrated.parent)
        }
        helper.succeed()
    }

    @GameTest
    fun aMigratedRegionStillCoversItsGroundInTheDimensionItWasBuiltIn(helper: GameTestHelper) {
        val server = helper.level.server
        val migrated = migrate(server)
        try {
            val regions = RegionService(migrated.resolve("regions.json"))
            val nether = checkNotNull(server.getLevel(Level.NETHER)) {
                "the nether is not loaded on the server"
            }

            val region = regions.regionAt(RegionWorlds.legacyName(nether.dimension()), 0, 70, 0)

            helper.assertValueEqual(
                region?.title ?: "<no region>",
                "Wanderer's Keep",
                "the region a migrated file puts at 0/70/0 of the nether",
            )
        } finally {
            deleteRecursively(migrated.parent)
        }
        helper.succeed()
    }

    /**
     * A miniature Portal deployment, migrated. The backend saves claim the
     * running server's own data version: what a genuine version jump needs is
     * vanilla's business (its file and data fixers run at boot), and this test
     * is about what the importer itself wrote.
     */
    private fun migrate(server: MinecraftServer): Path {
        val root = Files.createTempDirectory("mctraveler-migration")
        val portal = root.resolve("portal")
        val primary = portal.resolve("minecraft-server/primary")
        val secondary = portal.resolve("minecraft-server/secondary")
        write(portal.resolve("uuid-cache.json"), """{"$migrantUuid":"$migrantName"}""")
        // Last in Primary, so the Primary save is the one the migration makes
        // live and this server can actually read back. Their Secondary save
        // still becomes a Per-World Bucket in the record — legacy data now, and
        // the merge's input rather than this server's.
        write(
            portal.resolve("players/$migrantUuid.json"),
            """{"lastServer":"primary","notepad":["a migrated page"]}""",
        )
        write(
            portal.resolve("regions.json"),
            """{"regions":{"0":{"title":"Wanderer's Keep","start-x":-10,"start-z":-10,""" +
                """"end-x":10,"end-z":10,"world":"world_nether","members":["$migrantUuid"]}}}""",
        )
        for (level in listOf(primary.resolve("world"), secondary.resolve("last"))) {
            Files.createDirectories(level)
            NbtIo.writeCompressed(dataVersion(), level.resolve("level.dat"))
        }
        playerdata(primary.resolve("world"), "minecraft:the_nether", Triple(10.5, 70.0, -20.5))
        playerdata(secondary.resolve("last"), "minecraft:overworld", Triple(500.5, 71.0, 600.5))

        val target = root.resolve("run")
        PortalImport(
            ImportPlan(
                portalDir = portal,
                primaryServerDir = primary,
                secondaryServerDir = secondary,
                targetDir = target,
            ),
        ).run()
        return target
    }

    private fun playerdata(levelDir: Path, dimension: String, pos: Triple<Double, Double, Double>) {
        val tag = dataVersion().apply {
            putString("Dimension", dimension)
            put("Pos", ListTag().apply {
                add(DoubleTag.valueOf(pos.first))
                add(DoubleTag.valueOf(pos.second))
                add(DoubleTag.valueOf(pos.third))
            })
            put("Rotation", ListTag().apply {
                add(FloatTag.valueOf(0f))
                add(FloatTag.valueOf(0f))
            })
        }
        val file = levelDir.resolve("playerdata/${OfflineUuid.of(migrantName)}.dat")
        Files.createDirectories(file.parent)
        NbtIo.writeCompressed(tag, file)
    }

    private fun dataVersion() = CompoundTag().apply {
        putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version())
    }

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun deleteRecursively(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
