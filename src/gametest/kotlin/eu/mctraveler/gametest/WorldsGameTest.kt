package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.BuiltinDimensionTypes
import net.minecraft.world.level.dimension.DimensionType

/**
 * The two-World topology (spec stories 3, 19-21, 24): Primary is the vanilla
 * trio; Secondary is a datapack-defined trio shipped in the mod jar,
 * generation-identical to the vanilla trio.
 */
class WorldsGameTest {

    @GameTest
    fun secondaryTrioExistsMirroringTheVanillaTrio(helper: GameTestHelper) {
        val server = helper.level.server
        val expected: Map<ResourceKey<Level>, ResourceKey<DimensionType>> = mapOf(
            secondaryDimension("secondary") to BuiltinDimensionTypes.OVERWORLD,
            secondaryDimension("secondary_nether") to BuiltinDimensionTypes.NETHER,
            secondaryDimension("secondary_end") to BuiltinDimensionTypes.END,
        )
        for ((dimension, dimensionType) in expected) {
            val level = checkNotNull(server.getLevel(dimension)) {
                "the ${dimension.identifier()} dimension is not loaded on the server"
            }
            check(level.dimensionTypeRegistration().`is`(dimensionType)) {
                "${dimension.identifier()} has dimension type " +
                    "${level.dimensionTypeRegistration().registeredName} instead of $dimensionType"
            }
            check(level.seed == server.overworld().seed) {
                "${dimension.identifier()} does not share the world seed — " +
                    "generation cannot be identical to the vanilla trio"
            }
        }
        helper.succeed()
    }

    /**
     * Travel toggles between the two Worlds.
     *
     * This case used to assert the Portal's `Switching to <World>...` line
     * alongside the move, because `/switch` was the only way to Travel. That
     * message no longer exists anywhere: the merge turned `/switch` into a
     * signpost that moves nobody (ticket 08), and its wording is pinned in
     * [SwitchSignpostGameTest]. The toggle itself is untouched and asserted
     * here, straight through Travel, until Travel goes in ticket 09.
     */
    @GameTest(maxTicks = 600)
    fun travelTogglesBetweenTheTwoWorlds(helper: GameTestHelper) {
        val server = helper.level.server
        val player = TestPlayers.login(server, "SwitchTester")
        try {
            check(player.level().dimension() == Level.OVERWORLD) {
                "a brand-new player should start in Primary's overworld, " +
                    "not ${player.level().dimension().identifier()}"
            }

            player.travelToTheOtherWorld()
            helper.assertValueEqual(
                player.level().dimension(),
                secondaryDimension("secondary"),
                "the player's dimension after switching to Secondary",
            )

            player.travelToTheOtherWorld()
            helper.assertValueEqual(
                player.level().dimension(),
                Level.OVERWORLD,
                "the player's dimension after switching back to Primary",
            )
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun positionMemoryRestoresWhereThePlayerLastStoodPerWorld(helper: GameTestHelper) {
        val server = helper.level.server
        val player = TestPlayers.login(server, "PositionTester")
        try {
            // Stand at a known spot in Primary's *nether* — the bucket must
            // remember the dimension within the trio, not just coordinates.
            val nether = checkNotNull(server.getLevel(Level.NETHER))
            check(player.teleportTo(nether, 100.5, 40.0, -8.5, emptySet(), 90.0f, 10.0f, false))

            // First visit to Secondary lands at its spawn, on its own terrain.
            player.travelToTheOtherWorld()
            val secondary = checkNotNull(server.getLevel(secondaryDimension("secondary")))
            helper.assertValueEqual(
                player.level().dimension(),
                secondary.dimension(),
                "the World of a first visit",
            )
            val spawn = secondary.respawnData.pos()
            helper.assertValueEqual(
                player.x to player.z,
                spawn.x + 0.5 to spawn.z + 0.5,
                "first-visit landing column (the destination World's spawn)",
            )
            helper.assertTrue(
                player.y >= secondary.minY && player.y <= secondary.maxY,
                "first-visit landing height ${player.y} is outside the world",
            )

            // Stand somewhere distinct in Secondary, then Travel away and back:
            // each World must restore exactly where the player last stood.
            check(player.teleportTo(secondary, -50.5, 80.0, 30.5, emptySet(), 45.0f, -5.0f, false))
            player.travelToTheOtherWorld()
            helper.assertValueEqual(
                player.level().dimension(),
                Level.NETHER,
                "the dimension-within-trio restored by Primary's Position Memory",
            )
            helper.assertValueEqual(
                listOf(player.x, player.y, player.z),
                listOf(100.5, 40.0, -8.5),
                "the position restored by Primary's Position Memory",
            )
            helper.assertValueEqual(
                player.yRot to player.xRot,
                90.0f to 10.0f,
                "the rotation restored by Primary's Position Memory",
            )

            player.travelToTheOtherWorld()
            helper.assertValueEqual(
                player.level().dimension(),
                secondary.dimension(),
                "the World restored by Secondary's Position Memory",
            )
            helper.assertValueEqual(
                listOf(player.x, player.y, player.z),
                listOf(-50.5, 80.0, 30.5),
                "the position restored by Secondary's Position Memory",
            )
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun loggingBackInReturnsToTheWorldAndPositionLeft(helper: GameTestHelper) {
        val server = helper.level.server
        val store = checkNotNull(MCTraveler.persistence).players
        val uuid = UUID.randomUUID()

        val firstSession = TestPlayers.login(server, "ReturnTester", uuid)
        val secondary = checkNotNull(server.getLevel(secondaryDimension("secondary")))
        try {
            firstSession.travelToTheOtherWorld()
            helper.assertValueEqual(
                store.lastWorld(uuid) ?: "<unset>",
                "secondary",
                "lastWorld recorded on switch",
            )
            check(firstSession.teleportTo(secondary, 55.5, 90.0, -12.5, emptySet(), 0.0f, 0.0f, false))
        } finally {
            TestPlayers.logout(firstSession)
        }

        val secondSession = TestPlayers.login(server, "ReturnTester", uuid)
        try {
            helper.assertValueEqual(
                secondSession.level().dimension(),
                secondary.dimension(),
                "the World a returning player logs back into",
            )
            helper.assertValueEqual(
                listOf(secondSession.x, secondSession.y, secondSession.z),
                listOf(55.5, 90.0, -12.5),
                "the position a returning player logs back into",
            )
            helper.assertValueEqual(
                store.lastWorld(uuid) ?: "<unset>",
                "secondary",
                "lastWorld recorded on login",
            )
        } finally {
            TestPlayers.logout(secondSession)
        }

        // A brand-new player starts in Primary, and that is recorded at once.
        val newcomer = TestPlayers.login(server, "Newcomer")
        try {
            helper.assertValueEqual(
                newcomer.level().dimension(),
                Level.OVERWORLD,
                "the World a brand-new player starts in",
            )
            helper.assertValueEqual(
                store.lastWorld(newcomer.uuid) ?: "<unset>",
                "primary",
                "lastWorld recorded for a brand-new player",
            )
        } finally {
            TestPlayers.logout(newcomer)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun sharedPlayerStateRidesAlongUnchangedThroughTravel(helper: GameTestHelper) {
        val server = helper.level.server
        val player = TestPlayers.login(server, "StateTester")
        try {
            player.inventory.setItem(4, ItemStack(Items.DIAMOND, 5))
            player.enderChestInventory.setItem(0, ItemStack(Items.EMERALD, 3))
            player.giveExperienceLevels(7)
            player.health = 12.5f
            player.foodData.foodLevel = 13
            player.foodData.setSaturation(2.5f)

            fun assertStateIntact(after: String) {
                helper.assertValueEqual(
                    player.inventory.getItem(4).let { it.item to it.count },
                    Items.DIAMOND to 5,
                    "inventory $after",
                )
                helper.assertValueEqual(
                    player.enderChestInventory.getItem(0).let { it.item to it.count },
                    Items.EMERALD to 3,
                    "ender chest $after",
                )
                helper.assertValueEqual(player.experienceLevel, 7, "XP level $after")
                helper.assertValueEqual(player.health, 12.5f, "health $after")
                helper.assertValueEqual(player.foodData.foodLevel, 13, "hunger $after")
                helper.assertValueEqual(player.foodData.saturationLevel, 2.5f, "saturation $after")
            }

            player.travelToTheOtherWorld()
            helper.assertValueEqual(
                player.level().dimension(),
                secondaryDimension("secondary"),
                "the World after Travel",
            )
            assertStateIntact("after Travel to Secondary")

            player.travelToTheOtherWorld()
            assertStateIntact("after Travel back to Primary")
        } finally {
            TestPlayers.logout(player)
        }
        helper.succeed()
    }

    private fun secondaryDimension(path: String): ResourceKey<Level> =
        ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("mctraveler", path))
}
