package eu.mctraveler.gametest

import kotlin.math.abs
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Portal
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.storage.LevelData

/**
 * Each World is a complete, self-contained trio (spec stories 22-23): death
 * never crosses Worlds, and nether/end portals lead into the traveller's own
 * trio.
 */
class RespawnAndPortalsGameTest {

    @GameTest(maxTicks = 600)
    fun dyingWithNoRespawnPointLandsAtTheWorldOfDeathsSpawn(helper: GameTestHelper) {
        val server = helper.level.server
        var player: ServerPlayer = TestPlayers.login(server, "SpawnFaller")
        try {
            runSwitch(server, player)
            helper.assertValueEqual(
                player.level().dimension(),
                secondaryDimension("secondary"),
                "the World the player travelled to",
            )

            player = dieAndRespawn(server, player)
            helper.assertValueEqual(
                player.level().dimension(),
                secondaryDimension("secondary"),
                "the World a bedless death in Secondary respawns into",
            )
            assertNearSpawn(helper, player, "the bedless respawn in Secondary")

            // Primary keeps behaving exactly as vanilla does.
            runSwitch(server, player)
            player = dieAndRespawn(server, player)
            helper.assertValueEqual(
                player.level().dimension(),
                Level.OVERWORLD,
                "the World a bedless death in Primary respawns into",
            )
            assertNearSpawn(helper, player, "the bedless respawn in Primary")
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun aBedInPrimaryNeverCatchesADeathInSecondary(helper: GameTestHelper) {
        val server = helper.level.server
        val primary = level(server, Level.OVERWORLD)
        var player: ServerPlayer = TestPlayers.login(server, "OneBedOnly")
        try {
            sleepAt(player, primary, LONE_BED)
            runSwitch(server, player)

            // Secondary has no bed of this player's anywhere in it, so the
            // Primary bed must not reach across — Secondary's own spawn does.
            player = dieAndRespawn(server, player)
            helper.assertValueEqual(
                player.level().dimension(),
                secondaryDimension("secondary"),
                "the World a death in Secondary respawns into with a bed only in Primary",
            )
            assertNearSpawn(helper, player, "a death in Secondary with a bed only in Primary")

            // ...and the Primary bed is still waiting, untouched, back home.
            runSwitch(server, player)
            player = dieAndRespawn(server, player)
            assertWokeUpAt(helper, player, primary, LONE_BED, "a death back in Primary")
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun eachWorldsBedCatchesOnlyItsOwnDeaths(helper: GameTestHelper) {
        val server = helper.level.server
        val primary = level(server, Level.OVERWORLD)
        val secondary = level(server, secondaryDimension("secondary"))
        var player: ServerPlayer = TestPlayers.login(server, "BedKeeper")
        try {
            sleepAt(player, primary, PRIMARY_BED)
            runSwitch(server, player)
            sleepAt(player, secondary, SECONDARY_BED)

            player = dieAndRespawn(server, player)
            assertWokeUpAt(helper, player, secondary, SECONDARY_BED, "a death in Secondary")

            // The bed left behind in Primary is still Primary's, and only
            // Primary's — Travel swapped both respawn points with the buckets.
            runSwitch(server, player)
            player = dieAndRespawn(server, player)
            assertWokeUpAt(helper, player, primary, PRIMARY_BED, "a death in Primary")

            runSwitch(server, player)
            player = dieAndRespawn(server, player)
            assertWokeUpAt(helper, player, secondary, SECONDARY_BED, "a second death in Secondary")
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun netherPortalsLeadIntoTheTravellersOwnTrio(helper: GameTestHelper) {
        val server = helper.level.server
        val player = TestPlayers.login(server, "NetherWalker")
        try {
            // Primary is the vanilla trio and behaves exactly as vanilla does.
            assertPortalLeads(helper, server, Blocks.NETHER_PORTAL, player, Level.OVERWORLD, Level.NETHER)
            assertPortalLeads(helper, server, Blocks.NETHER_PORTAL, player, Level.NETHER, Level.OVERWORLD)
            // Secondary's nether is reachable only from Secondary, and leads back there.
            assertPortalLeads(
                helper, server, Blocks.NETHER_PORTAL, player,
                secondaryDimension("secondary"), secondaryDimension("secondary_nether"),
            )
            assertPortalLeads(
                helper, server, Blocks.NETHER_PORTAL, player,
                secondaryDimension("secondary_nether"), secondaryDimension("secondary"),
            )
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun endPortalsLeadIntoTheTravellersOwnTrio(helper: GameTestHelper) {
        val server = helper.level.server
        val player = TestPlayers.login(server, "EndWalker")
        try {
            assertPortalLeads(helper, server, Blocks.END_PORTAL, player, Level.OVERWORLD, Level.END)
            assertPortalLeads(helper, server, Blocks.END_PORTAL, player, Level.END, Level.OVERWORLD)
            assertPortalLeads(
                helper, server, Blocks.END_PORTAL, player,
                secondaryDimension("secondary"), secondaryDimension("secondary_end"),
            )
            assertPortalLeads(
                helper, server, Blocks.END_PORTAL, player,
                secondaryDimension("secondary_end"), secondaryDimension("secondary"),
            )
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun portalsKeepNonPlayersInTheirOwnTrioToo(helper: GameTestHelper) {
        val server = helper.level.server
        // Items, mobs and minecarts route by the World the portal stands in,
        // not by any player, so a dropped item never surfaces in the other
        // World's nether.
        assertPortalLeadsFor(
            helper, Blocks.NETHER_PORTAL,
            droppedStone(level(server, secondaryDimension("secondary"))),
            secondaryDimension("secondary_nether"),
        )
        assertPortalLeadsFor(
            helper, Blocks.END_PORTAL,
            droppedStone(level(server, secondaryDimension("secondary_end"))),
            secondaryDimension("secondary"),
        )
        helper.succeed()
    }

    private fun droppedStone(world: ServerLevel): ItemEntity =
        ItemEntity(world, 100.5, 70.0, 100.5, ItemStack(Items.STONE))

    /** Asserts the portal [player] steps into while standing in [from] leads to [to]. */
    private fun assertPortalLeads(
        helper: GameTestHelper,
        server: MinecraftServer,
        portal: Block,
        player: ServerPlayer,
        from: ResourceKey<Level>,
        to: ResourceKey<Level>,
    ) {
        val origin = level(server, from)
        check(player.teleportTo(origin, 100.5, 70.0, 100.5, emptySet(), 0.0f, 0.0f, false))
        assertPortalLeadsFor(helper, portal, player, to)
    }

    /**
     * Asserts the portal [entity] steps into leads to [to]. A portal's
     * destination is decided by the World the portal itself stands in, which is
     * the World [entity] is currently in.
     */
    private fun assertPortalLeadsFor(
        helper: GameTestHelper,
        portal: Block,
        entity: Entity,
        to: ResourceKey<Level>,
    ) {
        val origin = entity.level() as ServerLevel
        val what = "a ${portal.name.string} in ${origin.dimension().identifier()}"
        val transition = checkNotNull(
            (portal as Portal).getPortalDestination(origin, entity, entity.blockPosition()),
        ) { "$what led nowhere at all" }
        helper.assertValueEqual(transition.newLevel().dimension(), to, "where $what leads")
    }

    /** Asserts the player woke beside [bed], in [world] — vanilla stands them next to it, not on it. */
    private fun assertWokeUpAt(
        helper: GameTestHelper,
        player: ServerPlayer,
        world: ServerLevel,
        bed: BlockPos,
        what: String,
    ) {
        helper.assertValueEqual(player.level().dimension(), world.dimension(), "the World $what respawns into")
        helper.assertTrue(
            abs(player.x - bed.x) <= BED_STAND_UP_RADIUS &&
                abs(player.z - bed.z) <= BED_STAND_UP_RADIUS &&
                abs(player.y - bed.y) <= BED_STAND_UP_RADIUS,
            "$what put the player at ${player.x}, ${player.y}, ${player.z}, " +
                "not at the bed standing at ${bed.x}, ${bed.y}, ${bed.z}",
        )
    }

    /**
     * Leaves [player] with the respawn point sleeping in a bed at [foot] would
     * give them: a real bed, on cleared ground with room to stand up beside it.
     */
    private fun sleepAt(player: ServerPlayer, world: ServerLevel, foot: BlockPos) {
        for (dx in -3..3) for (dz in -3..3) {
            world.setBlock(foot.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS)
            for (dy in 0..2) {
                world.setBlock(foot.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)
            }
        }
        val bed = Blocks.BED.red().defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
        world.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_CLIENTS)
        world.setBlock(foot.north(), bed.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_CLIENTS)
        player.setRespawnPosition(
            ServerPlayer.RespawnConfig(LevelData.RespawnData.of(world.dimension(), foot, 0.0f, 0.0f), false),
            false,
        )
    }

    /** Asserts the player stands at the spawn column of the World they are in. */
    private fun assertNearSpawn(helper: GameTestHelper, player: ServerPlayer, what: String) {
        val spawn = player.level().respawnData.pos()
        helper.assertTrue(
            abs(player.x - spawn.x) <= SPAWN_SEARCH_RADIUS && abs(player.z - spawn.z) <= SPAWN_SEARCH_RADIUS,
            "$what put the player at ${player.x}, ${player.z}, " +
                "not at the World's spawn column ${spawn.x}, ${spawn.z}",
        )
    }

    /**
     * A real death and the client's "respawn" press. Returns the fresh
     * [ServerPlayer] the player list swaps in — respawning replaces the entity.
     */
    private fun dieAndRespawn(server: MinecraftServer, player: ServerPlayer): ServerPlayer {
        player.setGameMode(GameType.SURVIVAL)
        player.isInvulnerable = false
        // The two acknowledgements a real client sends before the server stops
        // shielding it — the dimension change is over, and its level has
        // rendered. Without both the player is invulnerable, and so unkillable.
        player.hasChangedDimension()
        player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
        player.kill(player.level())
        check(player.health <= 0.0f) { "the test player survived a deliberate kill" }
        player.connection.handleClientCommand(
            ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN),
        )
        val respawned = checkNotNull(server.playerList.getPlayer(player.uuid)) {
            "the player did not come back from the dead"
        }
        // Terrain is incidental to these tests: keep the respawned player safe
        // from whatever they landed in until the next deliberate death.
        respawned.isInvulnerable = true
        return respawned
    }

    private fun runSwitch(server: MinecraftServer, player: ServerPlayer) {
        server.commands.performPrefixedCommand(player.createCommandSourceStack(), "switch")
    }

    private fun level(server: MinecraftServer, dimension: ResourceKey<Level>): ServerLevel =
        checkNotNull(server.getLevel(dimension)) { "${dimension.identifier()} is not loaded" }

    private fun secondaryDimension(path: String): ResourceKey<Level> =
        ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("mctraveler", path))

    private companion object {
        /** How far vanilla's respawn placement may roam from the spawn block. */
        const val SPAWN_SEARCH_RADIUS = 16.0

        /** How far beside a bed vanilla stands a sleeper up. */
        const val BED_STAND_UP_RADIUS = 3.0

        // Fixed spots well clear of the World spawn, so "at the bed" and "at the
        // World's spawn" can never be confused for one another.
        val PRIMARY_BED: BlockPos = BlockPos(3000, 80, 3000)
        val SECONDARY_BED: BlockPos = BlockPos(-3000, 80, -3000)
        val LONE_BED: BlockPos = BlockPos(3000, 80, -3000)
    }
}
