package eu.mctraveler.gametest

import eu.mctraveler.dragonfight.ArenaLevels
import eu.mctraveler.hooks.Hooks
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.stats.Stats
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EntityType

class DragonFightGameTest {
    @GameTest(maxTicks = 200)
    fun dragonFightCreatesAnEndArenaAndCompletes(helper: net.minecraft.gametest.framework.GameTestHelper) {
        val player = MessageCapturingPlayer.join(helper, "DragonFighter")
        try {
            player.stats.setValue(player, Stats.ENTITY_KILLED.get(entityType("enderman")), 10)
            player.stats.setValue(player, Stats.ENTITY_KILLED.get(entityType("blaze")), 10)

            player.runCommand("dragonfight")
            helper.assertTrue(
                player.chatMessages.any { it.string.contains("This will begin a private dragon fight") },
                "the confirmation warning was shown",
            )
            player.messages.clear()
            player.chatMessages.clear()
            player.runCommand("dragonfight confirm")

            val key = ArenaLevels.key(player.uuid)
            val arena = helper.level.server.getLevel(key)!!
            helper.assertTrue(key in helper.level.server.levelKeys(), "the arena key is registered")
            helper.assertTrue(arena.dragonFight != null, "the arena has a dragon fight")
            helper.assertValueEqual(arena.worldBorder.size, 600.0, "the arena border")

            player.messages.clear()
            player.chatMessages.clear()
            player.runCommand("dragonfight")
            helper.assertValueEqual(
                player.dragonFightReplies(),
                listOf("ERROR You are already in your dragon fight"),
                "re-entering from inside the arena is refused",
            )

            val transition = Hooks.arenaExitPortal(arena!!, player)
            helper.assertTrue(transition != null, "the owner gets a respawn transition")
            player.teleport(transition!!)
            helper.runAfterDelay(5) {
                try {
                    helper.assertTrue(key !in helper.level.server.levelKeys(), "the arena level was deleted")
                    player.messages.clear()
                    player.chatMessages.clear()
                    player.runCommand("dragonfight")
                    helper.assertValueEqual(
                        player.dragonFightReplies(),
                        listOf("ERROR You have already freed the End. Try /rtp end"),
                        "completion permanently gates the command",
                    )

                    player.makeAdmin()
                    player.messages.clear()
                    player.chatMessages.clear()
                    player.runCommand("dragonfight reset DragonFighter")
                    player.messages.clear()
                    player.chatMessages.clear()
                    player.runCommand("dragonfight")
                    helper.assertTrue(
                        player.chatMessages.any { it.string.contains("This will begin a private dragon fight") },
                        "admin reset makes the fight available again",
                    )
                    helper.succeed()
                } finally {
                    player.leave()
                }
            }
        } catch (failure: Throwable) {
            player.leave()
            throw failure
        }
    }

    private fun entityType(path: String): EntityType<*> =
        BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(path))

    private fun MessageCapturingPlayer.dragonFightReplies(): List<String> =
        chatMessages.map { it.string }.filter { it.startsWith("SUCCESS ") || it.startsWith("ERROR ") }
}
