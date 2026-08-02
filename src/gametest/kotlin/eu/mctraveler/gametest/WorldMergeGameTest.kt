package eu.mctraveler.gametest

import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.importer.RespawnCheckReport
import eu.mctraveler.importer.WorldLayout
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Portal
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.storage.LevelResource

/**
 * The merge against a running server (ticket 11), which is the last line of
 * evidence before the operation is run for real.
 *
 * Everything upstream of here proves the merge against files: the plan, the
 * clip, the relocation, a block-for-block sampled diff, the audit and its
 * cross-checks, and every sweep. None of that says the *game* behaves. So this
 * suite runs a whole merge — the real tool, the real audit, the real sweeps —
 * and then does, on a booted server, the things a player would actually do with
 * what came out: open a chest that travelled, be turned away from someone's
 * Region at its new coordinates, die and wake on a bed that moved by a different
 * pass than the respawn point naming it, step into a portal and come out at its
 * own twin, and ask `/switch` where their other base went.
 *
 * [MergedSave] is the fixture, and the reason it is built the way it is matters
 * more here than anywhere else in the repo: it is the merge's own output, laid
 * into this server's own dimensions. A save assembled by hand with
 * `dimensions/mctraveler/secondary/` still in it would not fail on this build —
 * it would be ignored in silence, because retiring the Worlds subsystem stopped
 * the server creating those dimensions at all (ticket 09) — and every case below
 * would go green having read nothing.
 */
class WorldMergeGameTest {

    /**
     * The dimensions a merged save can still be reached through, and the three
     * it cannot.
     *
     * This is the case that states the trap rather than merely avoiding it. A
     * merged run directory still holds Secondary's dimension folders — the merge
     * copies, because the pre-merge backup is the rollback — and this server has
     * no way to open them. What makes the landmass reachable at all is that the
     * merge put it in Primary's folders instead, so the assertions run in both
     * directions: Secondary's dimensions do not exist here, and the relocated
     * chunk data is somewhere that does.
     */
    @GameTest(maxTicks = 600)
    fun aMergedSaveIsReachableOnlyThroughTheDimensionsThatStillExist(helper: GameTestHelper) {
        val server = helper.level.server
        val merged = MergedSave.of(server)

        for (dimension in listOf(Level.OVERWORLD, Level.NETHER, Level.END, EmbassiesFeature.DIMENSION)) {
            helper.assertTrue(
                server.getLevel(dimension) != null,
                "${dimension.identifier()} is not loaded, so a merged save has nowhere to be read from",
            )
        }
        for (role in DimensionRole.entries) {
            val secondary = WorldLayout.SECONDARY.dimension(role)
            helper.assertTrue(
                server.getLevel(secondary) == null,
                "${secondary.identifier()} exists on this server, which is the state in which a " +
                    "hand-built fixture would be read rather than silently ignored",
            )
        }

        helper.assertTrue(
            Files.isDirectory(merged.secondaryStorage(DimensionRole.OVERWORLD).resolve("region")),
            "the merged save no longer holds Secondary's own chunk data, so this fixture is not " +
                "the shape a real merge leaves behind",
        )
        for (role in listOf(DimensionRole.OVERWORLD, DimensionRole.NETHER)) {
            helper.assertTrue(
                merged.report.relocation.dimension(role).relocated > 0,
                "the merge relocated no chunks of Secondary's ${role.id}",
            )
            helper.assertTrue(
                Files.isDirectory(merged.primaryStorage(role).resolve("region")),
                "nothing arrived in ${merged.primaryStorage(role)}",
            )
        }
        helper.assertTrue(
            merged.report.sampled.compared > 0,
            "the merge compared no chunk against its source, so nothing here says the terrain arrived",
        )
        helper.succeed()
    }

    /**
     * A relocated chunk loads, and the chest standing in it still holds what was
     * in it (merge spec, User Story 8).
     *
     * The file tier already compares this chunk against its source block for
     * block. What it cannot say is that the result is something the game will
     * open: a chunk can satisfy every comparison the merge makes and still fail
     * to parse, and the failure mode is a chunk vanilla quietly regenerates from
     * Primary's own seed. That is why the chest is the assertion rather than the
     * terrain — flat ground regenerated from this server's seed has no chest in
     * it, so a chunk that did not arrive cannot pass.
     */
    @GameTest(maxTicks = 600)
    fun aRelocatedChestStillHoldsWhatWasInIt(helper: GameTestHelper) {
        val server = helper.level.server
        MergedSave.of(server)
        val overworld = level(server, Level.OVERWORLD)
        val at = MergedSave.merged(MergedSave.CHEST, DimensionRole.OVERWORLD)

        val chunk = ChunkPos.containing(at)
        overworld.getChunk(chunk.x, chunk.z)

        helper.assertTrue(
            overworld.getBlockState(at).`is`(Blocks.CHEST),
            "no chest arrived at $at, where the merge moved the homestead's",
        )
        val chest = checkNotNull(overworld.getBlockEntity(at) as? ChestBlockEntity) {
            "the chest at $at has no block entity, so nothing that was in it travelled with it"
        }
        val held = chest.getItem(0)
        helper.assertTrue(
            held.`is`(Items.DIAMOND) && held.count == MergedSave.DIAMONDS,
            "the relocated chest holds $held rather than the ${MergedSave.DIAMONDS} diamonds put in it",
        )
        // A merge offset is horizontal and cannot express anything else, so a
        // container that arrived one block up would be one nobody can find.
        helper.assertValueEqual(at.y, MergedSave.CHEST.y, "the height a relocated chest arrives at")
        helper.succeed()
    }

    /**
     * A relocated Region turns a stranger away at its new coordinates (merge
     * spec, User Story 21).
     *
     * Two passes of the merge have to agree for this to work and neither can
     * check the other: the Regions sweep moved the cuboid, the chunk relocation
     * moved the ground under it, and protection is only real where the two
     * landed on top of one another. So the block broken here is one the
     * relocation carried, inside a cuboid the sweep rewrote, read through the
     * live Region service exactly as a merged server would read its own file.
     */
    @GameTest(maxTicks = 600)
    fun aRelocatedRegionRefusesAStrangerWhereItLanded(helper: GameTestHelper) {
        val server = helper.level.server
        val merged = MergedSave.of(server)
        val overworld = level(server, Level.OVERWORLD)
        val at = MergedSave.merged(MergedSave.STONE, DimensionRole.OVERWORLD)

        val swept = merged.regions().single()
        helper.assertValueEqual(swept.title, MergedSave.REGION_TITLE, "the Region the merge swept")
        helper.assertValueEqual(
            swept.world,
            RegionWorlds.legacyName(Level.OVERWORLD),
            "the World a relocated Region comes to name",
        )
        helper.assertTrue(
            swept.contains(at.x, at.y, at.z),
            "the swept Region does not cover $at, where the relocation put the ground it protects",
        )

        // Added to the live tree rather than saved into it: what a merged server
        // reads at boot is this very object, and taking it out again afterwards
        // leaves this server's own regions.json untouched.
        val service = RegionsFeature.requireService()
        service.roots.add(swept)
        val stranger = MessageCapturingPlayer.join(helper, "T11Stranger")
        try {
            helper.assertTrue(
                overworld.getBlockState(at).`is`(Blocks.STONE),
                "no relocated block arrived at $at for the Region to be protecting",
            )
            stranger.isInvulnerable = true
            stranger.teleportTo(at.x + 0.5, MergedSave.FLOOR_Y + 1.0, at.z + 1.5)

            helper.assertFalse(
                stranger.gameMode.destroyBlock(at),
                "a stranger broke a block inside a relocated Region",
            )
            helper.assertTrue(
                overworld.getBlockState(at).`is`(Blocks.STONE),
                "the block a relocated Region protects was broken anyway",
            )
            helper.assertValueEqual(
                stranger.messages.last(),
                protectedBy(MergedSave.REGION_TITLE),
                "the refusal a relocated Region gives at its new coordinates",
            )
        } finally {
            service.roots.remove(swept)
            stranger.leave()
        }
        helper.succeed()
    }

    /**
     * A player whose respawn point the merge transformed dies and wakes on their
     * own bed (merge spec, User Story 28).
     *
     * This is the case with two authors. The respawn point was moved by the
     * player sweep and the bed it names was moved by the chunk relocation;
     * neither pass can see the other, each is perfectly self-consistent whatever
     * the other did, and a disagreement between them wakes somebody inside solid
     * rock. Ticket 07's cross-check compares the two at merge time, in the files
     * — asserted here as well, because it is the claim this case is the other
     * end of. What only a server can add is that vanilla agrees: it is vanilla
     * that reads the swept save, vanilla that looks for a bed at the coordinates
     * the save now claims, and vanilla that stands the player up beside it or
     * sends them to the world spawn saying their bed was missing.
     */
    @GameTest(maxTicks = 600)
    fun aTransformedRespawnPointWakesThePlayerOnTheirOwnBed(helper: GameTestHelper) {
        val server = helper.level.server
        val merged = MergedSave.of(server)
        val bed = MergedSave.merged(MergedSave.BED, DimensionRole.OVERWORLD)
        val standing = MergedSave.merged(MergedSave.STANDING, DimensionRole.OVERWORLD)

        helper.assertTrue(
            merged.report.section<RespawnCheckReport>().confirmed > 0,
            "the merge confirmed no respawn point against the relocated chunks, so there is no " +
                "file-level claim here for the game to agree or disagree with",
        )

        // The swept save put where a merged server keeps its playerdata, which is
        // the only door a login reads it through. Loom reuses the run directory
        // and the settler dies below, so it is laid down fresh each run rather
        // than found half-played.
        Files.copy(
            merged.playerSave(MergedSave.SETTLER),
            server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve("${MergedSave.SETTLER}.dat"),
            StandardCopyOption.REPLACE_EXISTING,
        )

        var settler: ServerPlayer = TestPlayers.login(server, MergedSave.SETTLER_NAME, MergedSave.SETTLER)
        try {
            helper.assertValueEqual(
                settler.level().dimension(),
                Level.OVERWORLD,
                "the dimension a swept Secondary save logs into",
            )
            helper.assertValueEqual(
                listOf(settler.x, settler.y, settler.z),
                listOf(standing.x, standing.y, standing.z),
                "where a swept Secondary save leaves the player standing",
            )

            settler = dieAndRespawn(server, settler)
            helper.assertValueEqual(
                settler.level().dimension(),
                Level.OVERWORLD,
                "the dimension a transformed respawn point wakes the player in",
            )
            helper.assertTrue(
                abs(settler.x - bed.x) <= BED_STAND_UP_RADIUS &&
                    abs(settler.y - bed.y) <= BED_STAND_UP_RADIUS &&
                    abs(settler.z - bed.z) <= BED_STAND_UP_RADIUS,
                "the settler woke at ${settler.x}, ${settler.y}, ${settler.z} rather than beside the " +
                    "bed the merge moved to $bed — which is what a respawn point and a bed that " +
                    "disagree looks like from the player's side",
            )
        } finally {
            TestPlayers.logout(settler)
        }
        helper.succeed()
    }

    /**
     * A relocated nether portal still leads to its own twin (merge spec, User
     * Story 9).
     *
     * This is what the offset's ÷8 is *for*, and the only test of it that
     * vanilla gets a vote in. The overworld portal moved 12288 blocks and the
     * nether one moved 1536, and the pair stays linked only because the second
     * is exactly an eighth of the first. Vanilla does not check that arithmetic;
     * it divides the overworld portal's coordinates down, looks for a
     * point-of-interest record within 128 blocks of the answer, and digs a fresh
     * portal when it does not find one.
     *
     * The twin is deliberately not at the scaled position, so that an exit which
     * landed there would be visibly a portal vanilla dug rather than the one the
     * merge carried — and the count of portals is asserted as well as the
     * landing, because "it found the twin" and "it built a new twin in roughly
     * the right place" are otherwise the same observation. Nothing may be dug.
     *
     * It also proves the point-of-interest records travelled. Vanilla links by
     * searching those records and never by looking at blocks, so a relocation
     * that moved every block of a portal and left its POI behind would fail here
     * and nowhere else in this suite.
     */
    @GameTest(maxTicks = 600)
    fun aRelocatedNetherPortalStillLeadsToItsTwin(helper: GameTestHelper) {
        val server = helper.level.server
        MergedSave.of(server)
        val overworld = level(server, Level.OVERWORLD)
        val nether = level(server, Level.NETHER)
        val portal = MergedSave.merged(MergedSave.PORTAL, DimensionRole.OVERWORLD)
        val twin = MergedSave.merged(MergedSave.TWIN_PORTAL, DimensionRole.NETHER)

        helper.assertTrue(
            overworld.getBlockState(portal).`is`(Blocks.NETHER_PORTAL),
            "no lit portal arrived at $portal, where the merge moved the homestead's",
        )
        helper.assertTrue(
            nether.getBlockState(twin).`is`(Blocks.NETHER_PORTAL),
            "no lit portal arrived at $twin, where the merge moved the twin",
        )
        // Vanilla links a pair by searching point-of-interest records rather
        // than by looking at blocks, so this is the half of the twin that
        // actually does the work — and the half a relocation could lose while
        // moving every block correctly.
        helper.assertTrue(
            nether.poiManager.existsAtPosition(PoiTypes.NETHER_PORTAL, twin),
            "the relocated twin at $twin has no point-of-interest record, so nothing vanilla looks " +
                "for when it links a portal pair arrived with it",
        )

        val before = portalsAround(nether, twin)

        // A portal's destination is decided by the portal rather than by whoever
        // steps into it, so a dropped stone asks the same question a player does.
        val traveller = ItemEntity(
            overworld,
            portal.x + 0.5,
            portal.y.toDouble(),
            portal.z + 0.5,
            ItemStack(Items.STONE),
        )
        val transition = checkNotNull(
            (Blocks.NETHER_PORTAL as Portal).getPortalDestination(overworld, traveller, portal),
        ) { "the relocated portal at $portal leads nowhere at all" }

        helper.assertValueEqual(
            transition.newLevel().dimension(),
            Level.NETHER,
            "where a relocated nether portal leads",
        )
        val landing = BlockPos.containing(transition.position())
        helper.assertTrue(
            abs(landing.x - twin.x) <= PORTAL_LANDING_RADIUS &&
                abs(landing.y - twin.y) <= PORTAL_LANDING_RADIUS &&
                abs(landing.z - twin.z) <= PORTAL_LANDING_RADIUS,
            "the relocated portal comes out at $landing rather than at its own twin, which the merge " +
                "moved to $twin — a portal vanilla had to dig for itself would land near " +
                "${BlockPos(portal.x / NETHER_SCALE, portal.y, portal.z / NETHER_SCALE)}",
        )
        helper.assertValueEqual(
            portalsAround(nether, twin),
            before,
            "how many nether portals stand around the twin — a new one means vanilla dug rather " +
                "than found, which is what an unlinked pair looks like from the player's side",
        )
        helper.succeed()
    }

    /** How many nether portals vanilla can see within its own search of [at]. */
    private fun portalsAround(nether: ServerLevel, at: BlockPos): Long =
        nether.poiManager.getInSquare(
            { it.`is`(PoiTypes.NETHER_PORTAL) },
            at,
            NETHER_PORTAL_SEARCH,
            PoiManager.Occupancy.ANY,
        ).count()

    /**
     * A real death and the client's own "respawn" press. Returns the fresh
     * [ServerPlayer] the player list swaps in, since respawning replaces the
     * entity.
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
        check(player.health <= 0.0f) { "the settler survived a deliberate kill" }
        player.connection.handleClientCommand(
            ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN),
        )
        val respawned = checkNotNull(server.playerList.getPlayer(player.uuid)) {
            "the settler did not come back from the dead"
        }
        respawned.isInvulnerable = true
        return respawned
    }

    private fun level(server: MinecraftServer, dimension: ResourceKey<Level>): ServerLevel =
        checkNotNull(server.getLevel(dimension)) { "${dimension.identifier()} is not loaded" }

    private companion object {
        /** How far beside a bed vanilla stands a sleeper up, as `RespawnAndPortalsGameTest` measures it. */
        const val BED_STAND_UP_RADIUS = 3.0

        /**
         * How far from the twin's own blocks a portal exit may land and still be
         * that twin. Vanilla puts the traveller inside the portal rectangle it
         * found, so a couple of blocks covers it — and the twin sits far enough
         * off the scaled position that no larger tolerance would be needed to
         * tell it from a portal vanilla dug itself.
         */
        const val PORTAL_LANDING_RADIUS = 4

        /** The ratio a nether portal links across, for naming where a dug portal would have landed. */
        const val NETHER_SCALE = 8

        /**
         * How far vanilla itself looks for a portal on the nether side, from
         * `PortalForcer.findClosestPortalPosition`. Counting over the same window
         * is what makes "nothing was dug" the same question vanilla answered.
         */
        const val NETHER_PORTAL_SEARCH = 16
    }
}
