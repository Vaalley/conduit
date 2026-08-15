package eu.mctraveler.gametest

import eu.mctraveler.MCTraveler
import java.nio.file.Files
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/**
 * Server wiring for the Persistence service: it comes up with the server,
 * rooted under the server's own run directory — the part the unit tier
 * (JsonPlayerStoreTest, NameCacheTest) cannot see.
 */
class PersistenceGameTest {
    @GameTest
    fun persistenceServiceIsLiveUnderTheServerRunDirectory(helper: GameTestHelper) {
        val persistence = checkNotNull(MCTraveler.persistence) {
            "the persistence service did not come up with the server"
        }
        val uuid = UUID.randomUUID()
        persistence.players.setLastWorld(uuid, "primary")
        check(persistence.players.lastWorld(uuid) == "primary") {
            "player store round-trip failed on the live server"
        }
        val file = helper.level.server.serverDirectory.resolve("mctraveler/players/$uuid.json")
        check(Files.exists(file)) {
            "player record was not written under the server run directory: $file"
        }
        helper.succeed()
    }
}
