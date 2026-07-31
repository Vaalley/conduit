package eu.mctraveler.crystal

import eu.mctraveler.text.Paint
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer

/**
 * Wiring for the Teleportation Crystal (spec User Stories 20-25, 37).
 *
 * The item, its recipes and the damage-bar rewrite need no runtime wiring at
 * all — recipes are datapack files and the rest is mixins. What lives here is
 * the recharge loop and the admin command.
 */
object CrystalFeature {

    /**
     * How often the recharge loop looks at the online players. A second is
     * plenty for a fifteen-minute clock, and it is Nucleus's own cadence.
     */
    const val REGEN_CHECK_INTERVAL_TICKS = 20

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(::onEndServerTick)
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            CrystalCommands.register(dispatcher)
        }
    }

    /**
     * Hands out recharges that have come due. Only online players are
     * considered — the clock is play time, so an offline player's threshold is
     * not getting any closer.
     */
    private fun onEndServerTick(server: MinecraftServer) {
        if (server.tickCount % REGEN_CHECK_INTERVAL_TICKS != 0) return
        for (player in server.playerList.players) {
            if (!CrystalEnergy.regen(player)) continue
            player.sendSystemMessage(Paint.info("Your energy crystal has recharged one energy"))
        }
    }
}
