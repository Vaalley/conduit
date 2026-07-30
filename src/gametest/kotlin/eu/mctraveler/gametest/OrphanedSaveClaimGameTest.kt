package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import eu.mctraveler.importer.OfflineUuid
import eu.mctraveler.importer.SaveQuarantine
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource

/**
 * Claiming an orphaned save at login, against a running server (ticket 20).
 *
 * The unit tier pins the decision and the file moves; what only a real login can
 * show is that a save put in place before `PlayerDataStorage.load` is the save
 * the player actually wakes up in — and that a player who already has one keeps
 * it.
 */
class OrphanedSaveClaimGameTest {

    private companion object {
        /** What the Portal-era backends stamped their saves with, as `PortalDeploymentFixture` does. */
        const val PORTAL_ERA_DATA_VERSION = 4536
    }

    private val claimantUuid: UUID = UUID.fromString("b7c1d2e3-4f50-4a61-9b72-c3d4e5f60718")
    private val claimantName = "Orphan"

    private val settledUuid: UUID = UUID.fromString("c8d2e3f4-5061-4b72-8c83-d4e5f6071829")
    private val settledName = "Settled"

    @GameTest(maxTicks = 600)
    fun aQuarantinedSaveIsClaimedByTheFirstLoginThatNamesIt(helper: GameTestHelper) {
        val server = helper.level.server
        // Loom reuses the gametest run directory, and the claimant plays on (and
        // is saved) every run — so this starts them as the fresh, never-seen
        // player a real cutover claim happens for.
        forget(server, claimantUuid)
        val quarantine = quarantine(server)
        try {
            // Their Portal record survived the migration (it was keyed by Mojang
            // uuid all along); only their saves could not be named.
            checkNotNull(MCTraveler.persistence).players.setLastWorld(claimantUuid, "secondary")
            // Their last World was Secondary; Primary still remembers where they stood.
            quarantineSave(quarantine, claimantName, "secondary", "minecraft:overworld", Triple(500.5, 71.0, 600.5)) {
                putInt("XpLevel", 42)
                put("Inventory", ListTag().apply { add(diamonds(7)) })
            }
            quarantineSave(quarantine, claimantName, "primary", "minecraft:the_nether", Triple(10.5, 70.0, -20.5))
            write(
                quarantine.advancements("secondary", OfflineUuid.of(claimantName)),
                """{"minecraft:story/mine_stone":{"done":true}}""",
            )

            val player = TestPlayers.login(server, claimantName, claimantUuid)
            try {
                helper.assertValueEqual(
                    player.level().dimension(),
                    ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("mctraveler", "secondary")),
                    "the World a claimed save puts the player in",
                )
                helper.assertValueEqual(
                    listOf(player.x, player.y, player.z),
                    listOf(500.5, 71.0, 600.5),
                    "the position a claimed save puts the player at",
                )
                helper.assertValueEqual(player.experienceLevel, 42, "the XP a claimed save carries")
                val held = player.inventory.getItem(0)
                helper.assertTrue(held.`is`(Items.DIAMOND) && held.count == 7, "the inventory a claimed save carries")

                // The other World's save became Primary's Per-World Bucket, so
                // Travel puts them back where the other backend had them.
                server.commands.performPrefixedCommand(player.createCommandSourceStack(), "switch")
                helper.assertValueEqual(
                    player.level().dimension(),
                    Level.NETHER,
                    "the dimension a claimed Per-World Bucket Travels to",
                )
                helper.assertValueEqual(
                    listOf(player.x, player.y, player.z),
                    listOf(10.5, 70.0, -20.5),
                    "the Position Memory a claimed save seeded",
                )

                // The claim consumed the quarantine, so nothing can be claimed twice.
                for (world in listOf("primary", "secondary")) {
                    for (file in quarantine.filesOf(world, OfflineUuid.of(claimantName))) {
                        helper.assertFalse(Files.exists(file), "$file survived the claim")
                    }
                }
            } finally {
                TestPlayers.logout(player)
            }
        } finally {
            clear(quarantine, claimantName)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun aPlayerWhoAlreadyHasASaveKeepsTheirOwn(helper: GameTestHelper) {
        val server = helper.level.server
        forget(server, settledUuid)
        val quarantine = quarantine(server)
        val home = helper.absolutePos(BlockPos(1, 1, 1))
        try {
            // A save of their own, as any player who has played here has.
            write(
                server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve("$settledUuid.dat"),
                save("minecraft:overworld", Triple(home.x + 0.5, home.y + 1.0, home.z + 0.5)) {
                    putInt("XpLevel", 3)
                    put("Inventory", ListTag().apply { add(diamonds(1)) })
                },
            )
            // ...and someone's quarantined save keyed to the same username, which
            // must never land on them: usernames change hands.
            quarantineSave(quarantine, settledName, "primary", "minecraft:overworld", Triple(-900.5, 70.0, -900.5)) {
                putInt("XpLevel", 99)
                put("Inventory", ListTag().apply { add(diamonds(64)) })
            }

            val player = TestPlayers.login(server, settledName, settledUuid)
            try {
                helper.assertValueEqual(player.experienceLevel, 3, "a live player's own XP")
                helper.assertValueEqual(player.inventory.getItem(0).count, 1, "a live player's own inventory")
                helper.assertValueEqual(
                    listOf(player.x, player.z),
                    listOf(home.x + 0.5, home.z + 0.5),
                    "a live player's own position",
                )
                helper.assertTrue(
                    Files.exists(quarantine.save("primary", OfflineUuid.of(settledName))),
                    "the orphan must be left alone, not consumed",
                )
                helper.assertTrue(
                    MCTraveler.persistence?.players?.bucket(settledUuid, "secondary") == null,
                    "no Per-World Bucket may be seeded onto a live player",
                )
            } finally {
                TestPlayers.logout(player)
            }
        } finally {
            clear(quarantine, settledName)
        }
        helper.succeed()
    }

    private fun quarantine(server: MinecraftServer): SaveQuarantine =
        SaveQuarantine.under(server.serverDirectory.resolve("mctraveler"))

    private fun quarantineSave(
        quarantine: SaveQuarantine,
        username: String,
        world: String,
        dimension: String,
        pos: Triple<Double, Double, Double>,
        extras: CompoundTag.() -> Unit = {},
    ) = write(quarantine.save(world, OfflineUuid.of(username)), save(dimension, pos, extras))

    /**
     * A backend save as a Portal-era vanilla server wrote it, stamped with a
     * data version older than this server's — which is the state every real
     * quarantined save is in. A claim happens long after the level itself was
     * upgraded, so nothing but `PlayerDataStorage.load`'s own
     * `DataFixTypes.PLAYER` pass stands between these bytes and the player.
     */
    private fun save(
        dimension: String,
        pos: Triple<Double, Double, Double>,
        extras: CompoundTag.() -> Unit = {},
    ): CompoundTag = CompoundTag().apply {
        putInt("DataVersion", PORTAL_ERA_DATA_VERSION)
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
        extras()
    }

    private fun diamonds(count: Int) = CompoundTag().apply {
        putByte("Slot", 0)
        putString("id", "minecraft:diamond")
        putInt("count", count)
    }

    /** Everything a server remembers about [uuid], gone — the state a first-ever login starts from. */
    private fun forget(server: MinecraftServer, uuid: UUID) {
        listOf(
            server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve("$uuid.dat"),
            server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve("$uuid.dat_old"),
            server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve("$uuid.json"),
            server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve("$uuid.json"),
            server.serverDirectory.resolve("mctraveler/players/$uuid.json"),
        ).forEach(Files::deleteIfExists)
    }

    private fun clear(quarantine: SaveQuarantine, username: String) {
        for (world in listOf("primary", "secondary")) {
            quarantine.filesOf(world, OfflineUuid.of(username)).forEach(Files::deleteIfExists)
        }
    }

    private fun write(file: Path, tag: CompoundTag) {
        Files.createDirectories(file.parent)
        NbtIo.writeCompressed(tag, file)
    }

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
