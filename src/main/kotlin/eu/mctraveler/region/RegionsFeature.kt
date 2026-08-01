package eu.mctraveler.region

import eu.mctraveler.MCTraveler
import eu.mctraveler.text.Paint
import java.util.UUID
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/**
 * Wiring for the Region service: brings the service up with the server
 * (regions live in `regions.json` in the run directory, the Portal's path),
 * registers the `/region` + `/rg` commands, and starts the current-region
 * tracking ([RegionTracker]) and protection ([RegionProtection]) that hang
 * off it.
 */
object RegionsFeature {

    /** The live Region service; null until the first server starts. */
    var service: RegionService? = null
        private set

    /** The guards [regionAt] folds over the service's answer (see [addLookupGuard]). */
    private val lookupGuards = mutableListOf<(String, Int, Int, Int, Region?) -> Region?>()

    fun requireService(): RegionService =
        checkNotNull(service) { "the Region service is not started" }

    /**
     * Admin means vanilla operator status (spec User Story 41): the vanilla
     * ops list is the single source of truth, managed by vanilla `/op` and
     * `/deop`. Checked against the list itself rather than a permission
     * level so the answer can never disagree with `ops.json`.
     */
    fun isAdmin(player: ServerPlayer): Boolean =
        player.level().server.playerList.isOp(player.nameAndId())

    /**
     * The refusal every admin-only command gives a player who is not one, or
     * null to let them through — the in-body gate the house rule asks for, so
     * a malformed invocation still gets its USAGE first. One copy, because the
     * refusal is a player-facing string and two of them would drift.
     */
    fun adminGate(player: ServerPlayer): Component? =
        if (isAdmin(player)) null
        else Paint.error("You must be an admin to use this command")

    /**
     * The username behind a member uuid — the online player's, else the name
     * cache's (deviation 10: a real cache, so member lists are complete).
     * Null only when the name is genuinely unknown, in which case that member
     * is invisible to `/rg locate`, `/rg remove` and the sidebar, exactly as
     * in the Portal.
     */
    fun usernameFor(server: MinecraftServer, uuid: UUID): String? =
        server.playerList.getPlayer(uuid)?.gameProfile?.name
            ?: MCTraveler.persistence?.names?.usernameFor(uuid)

    /**
     * The deepest region covering [pos] in [level], or null — the block-shaped
     * lookup protection is decided by ([RegionTracker.regionOf] is the
     * player-shaped one).
     */
    fun regionAt(level: Level, pos: BlockPos): Region? =
        regionAt(RegionWorlds.legacyName(level.dimension()), pos.x, pos.y, pos.z)

    /**
     * The one region lookup, in the Portal's legacy world strings: the live
     * tree's answer, then each guard's chance to change it.
     *
     * A guard is how a dimension can own ground the tree knows nothing about —
     * the embassies void answers with its synthetic world region (ADR 0003),
     * mirroring Nucleus's `getRegionAtGuards`. Guards are registered once at
     * mod init and outlive the service, so a restart cannot lose them.
     */
    fun regionAt(world: String, x: Int, y: Int, z: Int): Region? {
        var found = service?.regionAt(world, x, y, z)
        for (guard in lookupGuards) found = guard(world, x, y, z, found)
        return found
    }

    /** Adds a guard to every [regionAt] from now on. */
    fun addLookupGuard(guard: (world: String, x: Int, y: Int, z: Int, found: Region?) -> Region?) {
        lookupGuards.add(guard)
    }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            service = RegionService(server.serverDirectory.resolve("regions.json"))
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            RegionCommands.register(dispatcher)
        }
        RegionTracker.register()
        RegionProtection.register()
        // The Portal kept start markers per connection; dropping them on
        // disconnect preserves that lifetime.
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            RegionCommands.clearStartMarker(handler.player.uuid)
        }
    }
}
