package eu.mctraveler.gametest

import kotlin.math.abs
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
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
 * Respawning and portals, which are vanilla's own again.
 *
 * This suite existed to prove the opposite: that each World was a complete,
 * self-contained trio, that death never crossed Worlds, and that a portal led
 * into the traveller's own trio rather than into Primary's (the retired spec's
 * stories 22-23). All three claims were made by mixins that translated vanilla's
 * hardcoded dimension keys, and the merge deleted those mixins along with the
 * second World they served.
 *
 * What is kept is the single-World half of each case — a bedless death goes to
 * the world spawn, a bed catches a death beside it, a nether portal leads to the
 * nether and back, an end portal to the End and back. Those were the "Primary
 * behaves exactly as vanilla does" halves, and they are worth keeping precisely
 * because there are no longer any mixins standing between vanilla and the
 * player: they are what will catch it if something starts translating again.
 */
class RespawnAndPortalsGameTest {

    @GameTest(maxTicks = 600)
    fun dyingWithNoRespawnPointLandsAtTheWorldSpawn(helper: GameTestHelper) {
        val server = helper.level.server
        var player: ServerPlayer = TestPlayers.login(server, "SpawnFaller")
        try {
            player = dieAndRespawn(server, player)
            helper.assertValueEqual(
                player.level().dimension(),
                Level.OVERWORLD,
                "the dimension a bedless death respawns into",
            )
            assertNearSpawn(helper, player, "the bedless respawn")
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun aBedCatchesTheDeathOfThePlayerWhoSleptInIt(helper: GameTestHelper) {
        val server = helper.level.server
        val overworld = level(server, Level.OVERWORLD)
        var player: ServerPlayer = TestPlayers.login(server, "BedKeeper")
        try {
            sleepAt(player, overworld, LONE_BED)

            player = dieAndRespawn(server, player)
            assertWokeUpAt(helper, player, overworld, LONE_BED, "a death with a bed set")

            // And it goes on catching deaths, rather than being consumed by one.
            player = dieAndRespawn(server, player)
            assertWokeUpAt(helper, player, overworld, LONE_BED, "a second death with the same bed")
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun netherPortalsLeadToTheNetherAndBack(helper: GameTestHelper) {
        val server = helper.level.server
        val player = TestPlayers.login(server, "NetherWalker")
        try {
            assertPortalLeads(helper, server, Blocks.NETHER_PORTAL, player, Level.OVERWORLD, Level.NETHER)
            assertPortalLeads(helper, server, Blocks.NETHER_PORTAL, player, Level.NETHER, Level.OVERWORLD)
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun endPortalsLeadToTheEndAndBack(helper: GameTestHelper) {
        val server = helper.level.server
        val player = TestPlayers.login(server, "EndWalker")
        try {
            assertPortalLeads(helper, server, Blocks.END_PORTAL, player, Level.OVERWORLD, Level.END)
            assertPortalLeads(helper, server, Blocks.END_PORTAL, player, Level.END, Level.OVERWORLD)
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun portalsRouteEntitiesThatAreNotPlayersToo(helper: GameTestHelper) {
        val server = helper.level.server
        // A portal's destination is decided by the portal, not by any player
        // standing in it, so a dropped item finds the nether on its own.
        assertPortalLeadsFor(
            helper, Blocks.NETHER_PORTAL,
            droppedStone(level(server, Level.OVERWORLD)),
            Level.NETHER,
        )
        assertPortalLeadsFor(
            helper, Blocks.END_PORTAL,
            droppedStone(level(server, Level.END)),
            Level.OVERWORLD,
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

    private fun level(server: MinecraftServer, dimension: ResourceKey<Level>): ServerLevel =
        checkNotNull(server.getLevel(dimension)) { "${dimension.identifier()} is not loaded" }

    private companion object {
        /** How far vanilla's respawn placement may roam from the spawn block. */
        const val SPAWN_SEARCH_RADIUS = 16.0

        /** How far beside a bed vanilla stands a sleeper up. */
        const val BED_STAND_UP_RADIUS = 3.0

        /**
         * A fixed spot well clear of the world spawn, so "at the bed" and "at
         * the world spawn" can never be confused for one another.
         */
        val LONE_BED: BlockPos = BlockPos(3000, 80, -3000)
    }
}
